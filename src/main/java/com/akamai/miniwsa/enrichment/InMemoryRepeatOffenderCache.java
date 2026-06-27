package com.akamai.miniwsa.enrichment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRepeatOffenderCache implements RepeatOffenderCache {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int THRESHOLD = 5;
    private static final int MAX_ENTRIES = 10_000;

    // IP → deque of event timestamps (oldest first)
    private final ConcurrentHashMap<String, Deque<Instant>> store = new ConcurrentHashMap<>();

    /**
     * Returns true if there are more than THRESHOLD (5) prior events from this IP
     * within the rolling WINDOW (10 minutes) before eventTime.
     * The current event is NOT recorded here — call {@link #record} separately.
     */
    @Override
    public boolean isRepeatOffender(String clientIp, Instant eventTime) {
        Deque<Instant> deque = store.get(clientIp);
        if (deque == null) {
            return false;
        }
        synchronized (deque) {
            pruneDeque(deque, eventTime);
            return deque.size() >= THRESHOLD;
        }
    }

    /**
     * Records an event from clientIp at eventTime into the cache.
     * Must be called AFTER {@link #isRepeatOffender} to avoid counting the current event.
     */
    @Override
    public void record(String clientIp, Instant eventTime) {
        // Evict one entry if we're at the cap to prevent unbounded memory growth
        if (!store.containsKey(clientIp) && store.size() >= MAX_ENTRIES) {
            Iterator<String> it = store.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }

        Deque<Instant> deque = store.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(eventTime);
            pruneDeque(deque, eventTime);
        }
    }

    /**
     * Removes entries older than (reference - WINDOW) from the front of the deque.
     * Must be called under synchronization on the deque.
     */
    private void pruneDeque(Deque<Instant> deque, Instant reference) {
        Instant cutoff = reference.minus(WINDOW);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    /**
     * Scheduled cleanup: prune stale entries and remove empty deques to reclaim memory.
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
