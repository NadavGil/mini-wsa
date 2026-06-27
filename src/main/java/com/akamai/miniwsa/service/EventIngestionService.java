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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class EventIngestionService {

    private final EventRepository eventRepository;
    private final EnrichmentPipeline enrichmentPipeline;

    @Value("${wsa.ingestion.max-future-offset-minutes:5}")
    private int maxFutureOffsetMinutes;

    /**
     * Validates, enriches, and persists a batch of ingest requests.
     * The entire batch is atomic: one failure rolls back all inserts.
     *
     * @return list of persisted eventIds in insertion order
     */
    public List<String> ingest(List<EventIngestRequest> requests) {
        List<EnrichedEvent> events = requests.stream()
                .map(this::toEntity)
                .map(enrichmentPipeline::enrich)
                .collect(Collectors.toList());

        return eventRepository.saveAll(events).stream()
                .map(EnrichedEvent::getEventId)
                .collect(Collectors.toList());
    }

    private EnrichedEvent toEntity(EventIngestRequest req) {
        // Reject timestamps that are too far in the future
        Instant maxAllowed = Instant.now().plusSeconds((long) maxFutureOffsetMinutes * 60L);
        if (req.getTimestamp().isAfter(maxAllowed)) {
            throw new FutureTimestampException(
                    "Timestamp too far in the future: " + req.getTimestamp());
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
                .timestamp(req.getTimestamp())
                .configId(req.getConfigId())
                .policyId(req.getPolicyId())
                .clientIp(req.getClientIp())
                .hostname(req.getHostname())
                .path(sanitizePath(req.getPath()))
                .method(req.getMethod())
                .statusCode(req.getStatusCode())
                .userAgent(req.getUserAgent())
                .rule(rule)
                .action(req.getAction())
                .geoLocation(geo)
                .requestSize(req.getRequestSize())
                .responseSize(req.getResponseSize())
                .attackType("Unknown")    // overwritten by EnrichmentPipeline
                .threatScore(0)           // overwritten by EnrichmentPipeline
                .repeatOffender(false)    // overwritten by EnrichmentPipeline
                .ingestedAt(Instant.now())
                .build();
    }

    /** Strips CR, LF, and TAB to prevent log-injection attacks. */
    private String sanitizeLogField(String value) {
        if (value == null) return null;
        return value.replaceAll("[\r\n\t]", " ");
    }

    /** Removes HTML-significant chars from path to prevent reflected XSS. */
    private String sanitizePath(String path) {
        if (path == null) return null;
        return path.replaceAll("[<>\"'`]", "");
    }
}
