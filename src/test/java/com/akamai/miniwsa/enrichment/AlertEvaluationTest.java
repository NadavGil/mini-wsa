package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.AlertRule;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.dto.alerts.AlertEvaluationResult;
import com.akamai.miniwsa.dto.alerts.AlertRuleRequest;
import com.akamai.miniwsa.dto.alerts.AlertRuleResponse;
import com.akamai.miniwsa.repository.AlertRepository;
import com.akamai.miniwsa.repository.EventRepository;
import com.akamai.miniwsa.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertEvaluationTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private AlertService alertService;

    private AlertRule injectionRule;

    @BeforeEach
    void setUp() {
        injectionRule = AlertRule.builder()
                .id(UUID.randomUUID().toString())
                .name("High Injection Rate")
                .category(AttackCategory.INJECTION)
                .threshold(10)
                .windowMinutes(5)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void evaluateAll_firing_whenObservedExceedsThreshold() {
        when(alertRepository.findAll()).thenReturn(List.of(injectionRule));
        when(eventRepository.countByRuleCategoryAndTimestampOnOrAfter(
                eq(AttackCategory.INJECTION), any(Instant.class)))
                .thenReturn(15L);

        List<AlertEvaluationResult> results = alertService.evaluateAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).firing()).isTrue();
        assertThat(results.get(0).observedCount()).isEqualTo(15L);
    }

    @Test
    void evaluateAll_notFiring_whenBelowThreshold() {
        when(alertRepository.findAll()).thenReturn(List.of(injectionRule));
        when(eventRepository.countByRuleCategoryAndTimestampOnOrAfter(
                eq(AttackCategory.INJECTION), any(Instant.class)))
                .thenReturn(5L);

        List<AlertEvaluationResult> results = alertService.evaluateAll();

        assertThat(results.get(0).firing()).isFalse();
        assertThat(results.get(0).observedCount()).isEqualTo(5L);
    }

    @Test
    void evaluateAll_atThreshold_shouldFire() {
        when(alertRepository.findAll()).thenReturn(List.of(injectionRule));
        when(eventRepository.countByRuleCategoryAndTimestampOnOrAfter(
                eq(AttackCategory.INJECTION), any(Instant.class)))
                .thenReturn(10L);  // exactly at threshold

        List<AlertEvaluationResult> results = alertService.evaluateAll();

        assertThat(results.get(0).firing()).isTrue();
    }

    @Test
    void evaluateAll_emptyRules_returnsEmptyList() {
        when(alertRepository.findAll()).thenReturn(List.of());

        List<AlertEvaluationResult> results = alertService.evaluateAll();

        assertThat(results).isEmpty();
        verifyNoInteractions(eventRepository);
    }

    @Test
    void define_shouldPersistAndReturnRule() {
        AlertRuleRequest request = new AlertRuleRequest("DoS Alert", AttackCategory.DOS, 50, 10);
        when(alertRepository.count()).thenReturn(0L);
        when(alertRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));

        AlertRuleResponse response = alertService.define(request);

        assertThat(response.name()).isEqualTo("DoS Alert");
        assertThat(response.category()).isEqualTo(AttackCategory.DOS);
        assertThat(response.threshold()).isEqualTo(50);
        verify(alertRepository).save(any(AlertRule.class));
    }

    @Test
    void define_shouldThrow_whenMaxRulesReached() {
        when(alertRepository.count()).thenReturn(100L);

        AlertRuleRequest request = new AlertRuleRequest("Over Limit", AttackCategory.XSS, 5, 5);
        assertThatThrownBy(() -> alertService.define(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maximum number of alert rules");
    }
}
