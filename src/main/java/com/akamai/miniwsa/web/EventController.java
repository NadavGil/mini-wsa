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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "WAF event ingestion and sample retrieval")
public class EventController {

    @Value("${wsa.ingestion.max-batch-size:500}")
    private int maxBatchSize;

    private final EventIngestionService ingestionService;
    private final SamplesService samplesService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * POST /v1/events/ingest
     *
     * <p>Accepts a single event or an array of up to {@code wsa.ingestion.max-batch-size} events.
     * Validation (field constraints + future-timestamp check) runs synchronously so callers
     * receive an immediate 4xx on invalid input.
     *
     * <p>The DB write is asynchronous: events are handed off to the {@code ingestionExecutor}
     * thread pool and the HTTP thread returns {@code 202 Accepted} immediately. This decouples
     * HTTP throughput from DB write latency. In production this pool would be replaced by a
     * Kafka topic for durability and horizontal scaling.
     */
    @PostMapping("/ingest")
    @Operation(
        summary = "Ingest WAF events",
        description = "Submit a single event or a batch of up to 500 events. " +
                      "Returns 202 Accepted immediately; events are persisted asynchronously."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Events queued for async processing"),
        @ApiResponse(responseCode = "400", description = "Validation failure — missing or invalid fields",
                     content = @Content(schema = @Schema(ref = "#/components/schemas/ApiError"))),
        @ApiResponse(responseCode = "413", description = "Batch exceeds maximum size",
                     content = @Content(schema = @Schema(ref = "#/components/schemas/ApiError"))),
        @ApiResponse(responseCode = "422", description = "Event timestamp is too far in the future",
                     content = @Content(schema = @Schema(ref = "#/components/schemas/ApiError"))),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody JsonNode body) {
        List<EventIngestRequest> requests;

        if (body.isArray()) {
            if (body.size() > maxBatchSize) {
                throw new BatchTooLargeException(
                        "Batch size " + body.size() + " exceeds maximum of " + maxBatchSize);
            }
            requests = objectMapper.convertValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, EventIngestRequest.class));
        } else {
            requests = List.of(objectMapper.convertValue(body, EventIngestRequest.class));
        }

        // Synchronous validation: fail fast before touching the async queue.
        for (EventIngestRequest req : requests) {
            Set<ConstraintViolation<EventIngestRequest>> violations = validator.validate(req);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
        }
        ingestionService.validateTimestamps(requests);

        // IDs are caller-supplied — we can return them immediately without waiting for the DB write.
        List<String> ids = requests.stream().map(EventIngestRequest::getEventId).toList();

        // Fire-and-forget: the ingestionExecutor thread handles enrichment + DB write.
        ingestionService.ingestAsync(requests);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("queued", ids.size(), "eventIds", ids));
    }

    /**
     * GET /v1/events/samples
     */
    @GetMapping("/samples")
    @Operation(
        summary = "Retrieve event samples",
        description = "Returns a filtered, paginated slice of ingested events ordered by timestamp descending."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated event samples"),
        @ApiResponse(responseCode = "400", description = "Invalid query parameters")
    })
    public SamplesPageResponse samples(
            @Parameter(description = "Filter by customer config ID") @RequestParam(required = false) Long configId,
            @Parameter(description = "Start of time range (ISO-8601)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "End of time range (ISO-8601)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Filter by attack category") @RequestParam(required = false) AttackCategory category,
            @Parameter(description = "Filter by action") @RequestParam(required = false) ActionType action,
            @Parameter(description = "Max results per page (1–100)") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "0-indexed page number") @RequestParam(defaultValue = "0") int page) {
        return samplesService.findSamples(configId, from, to, category, action, limit, page);
    }
}
