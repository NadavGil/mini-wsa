package com.akamai.miniwsa.controller;

import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class EventIngestionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> buildEvent(String eventId) {
        return Map.of(
                "eventId", eventId,
                "timestamp", Instant.now().toString(),
                "configId", 1001,
                "clientIp", "10.0.0.1",
                "path", "/api/test",
                "method", "GET",
                "statusCode", 200,
                "rule", Map.of(
                        "ruleId", "R001",
                        "ruleName", "Test Rule",
                        "severity", Severity.HIGH.name(),
                        "category", AttackCategory.INJECTION.name()
                ),
                "action", ActionType.DENY.name()
        );
    }

    @Test
    void ingestSingleEvent_shouldReturn201() throws Exception {
        Map<String, Object> event = buildEvent(UUID.randomUUID().toString());

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(1)))
                .andExpect(jsonPath("$.eventIds", hasSize(1)));
    }

    @Test
    void ingestBatch_shouldReturn201WithAllIds() throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(buildEvent(UUID.randomUUID().toString()));
        }

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", is(5)))
                .andExpect(jsonPath("$.eventIds", hasSize(5)));
    }

    @Test
    void ingestDuplicateEventId_shouldReturn409() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = buildEvent(eventId);
        String body = objectMapper.writeValueAsString(event);

        // First ingestion should succeed
        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Duplicate should fail
        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void ingestMissingRequiredField_shouldReturn400() throws Exception {
        // eventId is missing
        Map<String, Object> event = Map.of(
                "timestamp", Instant.now().toString(),
                "configId", 1001,
                "clientIp", "10.0.0.1",
                "rule", Map.of(
                        "ruleId", "R001",
                        "severity", Severity.HIGH.name(),
                        "category", AttackCategory.XSS.name()
                ),
                "action", ActionType.ALERT.name()
        );

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingestFutureTimestamp_shouldReturn422() throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", UUID.randomUUID().toString(),
                "timestamp", Instant.now().plusSeconds(3600).toString(),  // 1 hour in future
                "configId", 1001,
                "clientIp", "10.0.0.1",
                "rule", Map.of(
                        "ruleId", "R001",
                        "severity", Severity.HIGH.name(),
                        "category", AttackCategory.INJECTION.name()
                ),
                "action", ActionType.DENY.name()
        );

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void ingestAdminPath_shouldHaveHigherThreatScore() throws Exception {
        Map<String, Object> adminEvent = new java.util.HashMap<>(buildEvent(UUID.randomUUID().toString()));
        adminEvent.put("path", "/admin/settings");
        adminEvent.put("action", ActionType.DENY.name());

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminEvent)))
                .andExpect(status().isCreated());
        // Score verification done via samples endpoint in StatsIntegrationTest
    }
}
