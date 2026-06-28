package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.RuleInfo;
import com.akamai.miniwsa.dto.ingest.EventIngestRequest;
import com.akamai.miniwsa.enrichment.EnrichmentPipeline;
import com.akamai.miniwsa.repository.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * Core ingestion service. Two entry points:
 * <ul>
 *   <li>{@link #ingest} — synchronous; used by tests and internal callers.</li>
 *   <li>{@link #ingestAsync} — async (via {@code @Async}); called by the controller
 *       so the HTTP thread can return {@code 202 Accepted} without waiting for the
 *       DB write. This is the canonical WAF pipeline pattern — the HTTP layer is
 *       decoupled from storage, giving linear throughput scaling with the worker pool.
 *       Production systems replace the thread pool with a Kafka topic for durability.</li>
 * </ul>
 *
 * <p>{@code @Transactional} is on individual methods, not the class, so helper
 * methods don't accidentally inherit transactional semantics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventIngestionService {

    private final EventRepository eventRepository;
    private final EnrichmentPipeline enrichmentPipeline;
    private final MeterRegistry meterRegistry;

    @Value("${wsa.ingestion.max-future-offset-minutes:5}")
    private int maxFutureOffsetMinutes;

    // Lazy-init counters — avoids constructor injection ordering issues with Micrometer
    private Counter eventsAcceptedCounter() {
        return Counter.builder("wsa.events.accepted")
                .description("Total events successfully ingested and committed")
                .register(meterRegistry);
    }

    private Counter eventsRejectedCounter() {
        return Counter.builder("wsa.events.rejected")
                .description("Total events rejected (duplicate, future timestamp, validation)")
                .register(meterRegistry);
    }

    /**
     * Validates that no event in the batch has a future timestamp beyond the allowed offset.
     * Called synchronously by the controller BEFORE submitting to the async queue,
     * so callers receive an immediate 422 rather than a silent async failure.
     */
    public void validateTimestamps(List<EventIngestRequest> requests) {
        Instant maxAllowed = Instant.now().plusSeconds((long) maxFutureOffsetMinutes * 60L);
        for (EventIngestRequest req : requests) {
            if (req.getTimestamp() != null && req.getTimestamp().isAfter(maxAllowed)) {
                throw new FutureTimestampException("Timestamp too far in the future: " + req.getTimestamp());
            }
        }
    }

    /**
     * Async entry point: submits the batch to the {@code ingestionExecutor} thread pool
     * and returns immediately. The caller receives {@code 202 Accepted} without waiting
     * for the DB write.
     *
     * <p>Duplicate event IDs are handled gracefully: if a duplicate slips through
     * (possible with async submit before the first event is committed), the DB unique
     * constraint fires, is caught here, and logged as a warning — the pipeline does not crash.
     *
     * <p>Note: {@code @Async} + {@code @Transactional} work together because the controller
     * calls through the Spring proxy. The async executor thread starts a fresh transaction.
     */
    @Async("ingestionExecutor")
    @Transactional
    public void ingestAsync(List<EventIngestRequest> requests) {
        try {
            doIngest(requests);
        } catch (DataIntegrityViolationException e) {
            log.warn("Async ingest: duplicate eventId in batch — event(s) discarded. {}", e.getMessage());
            eventsRejectedCounter().increment();
        } catch (Exception e) {
            log.error("Async ingest failed: {}", e.getMessage(), e);
            eventsRejectedCounter().increment();
        }
    }

    /**
     * Synchronous entry point. Used by integration tests that need the events
     * in the DB before making assertions.
     */
    @Transactional
    public List<String> ingest(List<EventIngestRequest> requests) {
        return doIngest(requests);
    }

    /**
     * Core enrichment + persistence logic shared by both sync and async paths.
     *
     * Three-phase enrichment prevents cache poisoning on DB rollback:
     *   Phase 1 — enrichWithoutRecording(): compute all fields; no cache writes.
     *   Phase 2 — saveAll(): atomic DB write inside the current transaction.
     *   Phase 3 — afterCommit hook: writes to the repeat-offender cache only after
     *              the transaction commits durably. Skipped entirely on rollback.
     */
    private List<String> doIngest(List<EventIngestRequest> requests) {
        // Phase 1: enrich (no cache side-effects)
        List<EnrichedEvent> events = requests.stream()
                .map(this::toEntity)
                .map(enrichmentPipeline::enrichWithoutRecording)
                .toList();

        // Phase 2: persist atomically — on rollback the afterCommit hook never fires
        List<EnrichedEvent> saved = eventRepository.saveAll(events);

        // Phase 3: record in cache AFTER commit.
        List<EnrichedEvent> snapshot = List.copyOf(saved);
        int count = snapshot.size();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                snapshot.forEach(enrichmentPipeline::recordInCache);
                eventsAcceptedCounter().increment(count);
            }
        });

        return saved.stream()
                .map(EnrichedEvent::getEventId)
                .toList();
    }

    private EnrichedEvent toEntity(EventIngestRequest req) {
        Instant ts = req.getTimestamp();

        RuleInfo rule = null;
        if (req.getRule() != null) {
            rule = RuleInfo.builder()
                    .ruleId(req.getRule().getRuleId())
                    .ruleName(req.getRule().getRuleName())
                    .ruleMessage(sanitizeLogField(req.getRule().getRuleMessage()))
                    .severity(req.getRule().getSeverity())
                    .category(req.getRule().getCategory())
                    .build();
        }

        GeoLocation geo = null;
        if (req.getGeoLocation() != null) {
            geo = GeoLocation.builder()
                    .country(req.getGeoLocation().getCountry())
                    .city(req.getGeoLocation().getCity())
                    .build();
        }

        return EnrichedEvent.builder()
                .eventId(req.getEventId())
                .timestamp(ts)
                .configId(req.getConfigId())
                .policyId(req.getPolicyId())
                .clientIp(req.getClientIp())
                .hostname(req.getHostname())
                .path(req.getPath())        // Raw path preserved: JSON serialisation handles XSS; forensic fidelity matters
                .method(req.getMethod())
                .statusCode(req.getStatusCode())
                .userAgent(req.getUserAgent())
                .rule(rule)
                .action(req.getAction())
                .geoLocation(geo)
                .requestSize(req.getRequestSize())
                .responseSize(req.getResponseSize())
                .attackType("Unknown")      // overwritten by EnrichmentPipeline
                .threatScore(0)             // overwritten by EnrichmentPipeline
                .repeatOffender(false)      // overwritten by EnrichmentPipeline
                .receivedAt(Instant.now())
                .build();
    }

    /** Strips CR, LF, and TAB from log fields to prevent log injection. */
    private String sanitizeLogField(String value) {
        if (value == null) return null;
        return value.replaceAll("[\r\n\t]", " ");
    }
}
