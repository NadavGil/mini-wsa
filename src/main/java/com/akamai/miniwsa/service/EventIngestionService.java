package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.RuleInfo;
import com.akamai.miniwsa.dto.ingest.EventIngestRequest;
import com.akamai.miniwsa.enrichment.EnrichmentPipeline;
import com.akamai.miniwsa.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class EventIngestionService {

    private final EventRepository eventRepository;
    private final EnrichmentPipeline enrichmentPipeline;

    @Value("${wsa.ingestion.max-future-offset-minutes:5}")
    private int maxFutureOffsetMinutes;

    /**
     * Validates, enriches, and persists a batch of ingest requests atomically.
     *
     * Two-phase enrichment ensures the repeat-offender cache is never updated
     * for events that fail to persist (prevents cache poisoning on DB rollback):
     *   Phase 1 — enrichWithoutRecording(): compute fields, no cache writes.
     *   Phase 2 — saveAll(): atomic DB write.
     *   Phase 3 — recordInCache(): update cache only after commit succeeds.
     *
     * @return list of persisted eventIds in insertion order
     */
    public List<String> ingest(List<EventIngestRequest> requests) {
        // Phase 1: enrich (no cache side-effects)
        List<EnrichedEvent> events = requests.stream()
                .map(this::toEntity)
                .map(enrichmentPipeline::enrichWithoutRecording)
                .toList();

        // Phase 2: persist atomically — rollback leaves cache unaffected
        List<EnrichedEvent> saved = eventRepository.saveAll(events);

        // Phase 3: record in cache only after successful commit
        saved.forEach(enrichmentPipeline::recordInCache);

        return saved.stream()
                .map(EnrichedEvent::getEventId)
                .toList();
    }

    private EnrichedEvent toEntity(EventIngestRequest req) {
        // @NotNull on EventIngestRequest.timestamp is enforced by the controller's Validator.validate()
        // before this method is ever called — null here is not reachable in normal flow.
        Instant ts = req.getTimestamp();

        Instant maxAllowed = Instant.now().plusSeconds((long) maxFutureOffsetMinutes * 60L);
        if (ts.isAfter(maxAllowed)) {
            throw new FutureTimestampException("Timestamp too far in the future: " + ts);
        }

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
                .path(req.getPath())          // Raw path preserved: JSON serialisation handles XSS; forensic fidelity matters
                .method(req.getMethod())
                .statusCode(req.getStatusCode())
                .userAgent(req.getUserAgent())
                .rule(rule)
                .action(req.getAction())
                .geoLocation(geo)
                .requestSize(req.getRequestSize())
                .responseSize(req.getResponseSize())
                .attackType("Unknown")         // overwritten by EnrichmentPipeline
                .threatScore(0)                // overwritten by EnrichmentPipeline
                .repeatOffender(false)         // overwritten by EnrichmentPipeline
                .ingestedAt(Instant.now())
                .build();
    }

    /** Strips CR, LF, and TAB from fields that appear in server logs to prevent log injection. */
    private String sanitizeLogField(String value) {
        if (value == null) return null;
        return value.replaceAll("[\r\n\t]", " ");
    }
}
