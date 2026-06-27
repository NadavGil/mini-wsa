package com.akamai.miniwsa.web;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.dto.ingest.EventIngestRequest;
import com.akamai.miniwsa.dto.samples.SamplesPageResponse;
import com.akamai.miniwsa.service.BatchTooLargeException;
import com.akamai.miniwsa.service.EventIngestionService;
import com.akamai.miniwsa.service.SamplesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class EventController {

    private static final int MAX_BATCH_SIZE = 500;

    private final EventIngestionService ingestionService;
    private final SamplesService samplesService;
    private final ObjectMapper objectMapper;

    /**
     * POST /v1/events/ingest
     * Accepts a single event object or an array of up to 500 events.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody JsonNode body) {
        List<EventIngestRequest> requests;

        if (body.isArray()) {
            if (body.size() > MAX_BATCH_SIZE) {
                throw new BatchTooLargeException(
                        "Batch size " + body.size() + " exceeds maximum of " + MAX_BATCH_SIZE);
            }
            requests = objectMapper.convertValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, EventIngestRequest.class));
        } else {
            EventIngestRequest single = objectMapper.convertValue(body, EventIngestRequest.class);
            requests = List.of(single);
        }

        // Manual validation for batch (Jackson conversion bypasses @Valid)
        for (EventIngestRequest req : requests) {
            var violations = jakarta.validation.Validation
                    .buildDefaultValidatorFactory()
                    .getValidator()
                    .validate(req);
            if (!violations.isEmpty()) {
                throw new jakarta.validation.ConstraintViolationException(violations);
            }
        }

        List<String> ids = ingestionService.ingest(requests);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("accepted", ids.size(), "eventIds", ids));
    }

    /**
     * GET /v1/events/samples
     */
    @GetMapping("/samples")
    public SamplesPageResponse samples(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AttackCategory category,
            @RequestParam(required = false) ActionType action,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return samplesService.findSamples(configId, from, to, category, action, limit, offset);
    }
}
