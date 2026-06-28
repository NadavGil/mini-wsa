package com.akamai.miniwsa.controller;

import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class EventIngestionIntegrationTest {

    /**
     * Override the async ingestion executor with a synchronous one for tests.
     * Without this, ingestAsync() returns before the DB write completes, causing
     * race conditions in assertions. SyncTaskExecutor runs the task on the calling
     * thread, making async behaviour deterministic in the test context.
     */
    @TestConfiguration
    static class SyncAsyncConfig {
        @Bean(name = "ingestionExecutor")
        public Executor ingestionExecutor() {
            return new SyncTaskExecutor();
        }
    }

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
    void ingestSingleEvent_shouldReturn202() throws Exception {
        Map<String, Object> event = buildEvent(UUID.randomUUID().toString());

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isAccepted())   // 202 — async pipeline
                .andExpect(jsonPath("$.queued", is(1)))
                .andExpect(jsonPath("$.eventIds", hasSize(1)));
    }

    @Test
    void ingestBatch_shouldReturn202WithAllIds() throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(buildEvent(UUID.randomUUID().toString()));
        }

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued", is(5)))
                .andExpect(jsonPath("$.eventIds", hasSize(5)));
    }

    @Test
    void ingestDuplicateEventId_bothReturn202_duplicateHandledGracefully() throws Exception {
        // With async ingestion, duplicate detection happens at the DB layer (UNIQUE constraint
        // on event_id), not at the HTTP layer. Both requests return 202 Accepted; the second
        // one is silently discarded by the async task when the constraint fires. This is the
        // correct behaviour for a WAF pipeline — at-least-once delivery with idempotent inserts.
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = buildEvent(eventId);
        String body = objectMapper.writeValueAsString(event);

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        // Second ingest with the same ID: also 202 (duplicate handled async, not rejected synchronously)
        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void ingestMissingRequiredField_shouldReturn400() throws Exception {
        // eventId is missing — field validation is synchronous, returns 400 immediately
        Map<String, Object> event = Map.of(
                "timestamp", Instant.now().toString(),
                "configId", 1001,
                "clientIp", "10.0.0.1",
                "rule", Map.of(
                        "ruleId", "R001",
                        "ruleName", "Test Rule",
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
    void ingestNullTimestamp_shouldReturn400NotNPE() throws Exception {
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("configId", 1001);
        event.put("clientIp", "10.0.0.1");
        event.put("rule", Map.of(
                "ruleId", "R001",
                "ruleName", "Test Rule",
                "severity", Severity.HIGH.name(),
                "category", AttackCategory.INJECTION.name()
        ));
        event.put("action", ActionType.DENY.name());

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingestFutureTimestamp_shouldReturn422() throws Exception {
        // Timestamp validation is synchronous — caller gets an immediate 422
        Map<String, Object> event = Map.of(
                "eventId", UUID.randomUUID().toString(),
                "timestamp", Instant.now().plusSeconds(3600).toString(),
                "configId", 1001,
                "clientIp", "10.0.0.1",
                "rule", Map.of(
                        "ruleId", "R001",
                        "ruleName", "Test Rule",
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
    void ingestInvalidClientIp_shouldReturn400() throws Exception {
        // clientIp now validated with @Pattern — non-IP strings rejected synchronously
        Map<String, Object> event = new java.util.HashMap<>(buildEvent(UUID.randomUUID().toString()));
        event.put("clientIp", "not-an-ip-address!");

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ingestAdminPath_shouldReturn202() throws Exception {
        Map<String, Object> adminEvent = new java.util.HashMap<>(buildEvent(UUID.randomUUID().toString()));
        adminEvent.put("path", "/admin/settings");

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminEvent)))
                .andExpect(status().isAccepted());
    }
}
