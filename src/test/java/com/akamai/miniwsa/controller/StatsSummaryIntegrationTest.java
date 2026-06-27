package com.akamai.miniwsa.controller;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class StatsSummaryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final long CONFIG_ID = 9999L;

    @BeforeEach
    void seedEvents() throws Exception {
        // Ingest a few events for CONFIG_ID to ensure stats return data
        for (int i = 0; i < 3; i++) {
            Map<String, Object> event = Map.of(
                    "eventId", UUID.randomUUID().toString(),
                    "timestamp", Instant.now().toString(),
                    "configId", CONFIG_ID,
                    "clientIp", "10.1.1." + i,
                    "path", "/api/resource",
                    "method", "POST",
                    "statusCode", 403,
                    "rule", Map.of(
                            "ruleId", "R00" + i,
                            "severity", Severity.HIGH.name(),
                            "category", AttackCategory.INJECTION.name()
                    ),
                    "action", ActionType.DENY.name()
            );
            mockMvc.perform(post("/v1/events/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(event)));
        }
    }

    @Test
    void statsSummary_shouldReturnTotalCount() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("configId", String.valueOf(CONFIG_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId", is((int) CONFIG_ID)))
                .andExpect(jsonPath("$.totalEvents", greaterThanOrEqualTo(3)));
    }

    @Test
    void statsSummary_shouldIncludeCategoryBreakdown() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("configId", String.valueOf(CONFIG_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byCategory", not(empty())));
    }

    @Test
    void statsSummary_shouldIncludeActionBreakdown() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("configId", String.valueOf(CONFIG_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byAction", not(empty())));
    }

    @Test
    void statsSummary_invalidRange_shouldReturn400() throws Exception {
        mockMvc.perform(get("/v1/stats/summary")
                        .param("from", Instant.now().toString())
                        .param("to", Instant.now().minusSeconds(3600).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void samplesShouldReturnPaginatedResults() throws Exception {
        mockMvc.perform(get("/v1/events/samples")
                        .param("configId", String.valueOf(CONFIG_ID))
                        .param("limit", "2")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit", is(2)))
                .andExpect(jsonPath("$.offset", is(0)))
                .andExpect(jsonPath("$.events", hasSize(lessThanOrEqualTo(2))));
    }
}
