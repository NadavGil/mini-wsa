package com.akamai.miniwsa.enrichment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRepeatOffenderCache implements RepeatOffenderCache {

    // Values injected from application.yml — operators can tune without recompiling
    private final Duration window;
    private final int threshold;
    private final int maxEntries;

    public InMemoryRepeatOffenderCache(
            @Value("${wsa.cache.window-minutes:10}") int windowMinutes,
            @Value("${wsa.cache.repeat-offender-threshold:5}") int threshold,
            @Value("${wsa.cache.max-ip-entries:10000}") int maxEntries) {
        this.window = Duration.ofMinutes(windowMinutes);
        this.threshold = threshold;
        this.maxEntries = maxEntries;
    }

    // IP → deque of event timestamps (oldest first)
    private final ConcurrentHashMap<String, Deque<Instant>> store = new ConcurrentHashMap<>();

    /**
     * Returns true if there are >= threshold prior events from this IP within the
     * rolling window before eventTime. The current event is NOT recorded here.
     * Call {@link #record} separately after the DB write succeeds.
     */
    @Override
    public boolean isRepeatOffender(String clientIp, Instant eventTime) {
        Deque<Instant> deque = store.get(clientIp);
        if (deque == null) {
            return false;
        }
        // If the scheduler just removed this deque, it was empty → not a repeat offender. Correct.
        synchronized (deque) {
            pruneDeque(deque, eventTime);
            return deque.size() >= threshold;
        }
    }

    /**
     * Records an event from clientIp at eventTime.
     * Uses ConcurrentHashMap.compute() which is atomic per key bucket, eliminating
     * the orphaned-deque race where the scheduler removes a deque between
     * computeIfAbsent and the subsequent synchronized block.
     */
    @Override
    public void record(String clientIp, Instant eventTime) {
        store.compute(clientIp, (k, existing) -> {
            // Soft cap: skip new IPs when at capacity
            if (existing == null && store.size() >= maxEntries) {
                return null;
            }
            Deque<Instant> deque = (existing != null) ? existing : new ArrayDeque<>();
            deque.addLast(eventTime);
            pruneDeque(deque, eventTime);
            return deque;
        });
    }

    /**
     * Removes entries older than (reference - window) from the front of the deque.
     * Must be called under synchronization on the deque (or inside compute()).
     */
    private void pruneDeque(Deque<Instant> deque, Instant reference) {
        Instant cutoff = reference.minus(window);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    /**
     * Scheduled cleanup: prune stale entries and remove empty deques to reclaim memory.
     * Uses remove(key, value) which is atomic — safe against concurrent record() calls.
     */
    @Scheduled(fixedRate = 60_000)
    public void pruneEmptyDeques() {
        Instant now = Instant.now();
        store.forEach((ip, deque) -> {
            synchronized (deque) {
                pruneDeque(deque, now);
                if (deque.isEmpty()) {
                    store.remove(ip, deque);
                }
            }
        });
    }
}
