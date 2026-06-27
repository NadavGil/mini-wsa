package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.AlertRule;
import com.akamai.miniwsa.dto.alerts.AlertEvaluationResult;
import com.akamai.miniwsa.dto.alerts.AlertRuleRequest;
import com.akamai.miniwsa.dto.alerts.AlertRuleResponse;
import com.akamai.miniwsa.repository.AlertRepository;
import com.akamai.miniwsa.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;
    private final EventRepository eventRepository;

    @Value("${wsa.alerts.max-rules:100}")
    private int maxRules;

    @Transactional
    public AlertRuleResponse define(AlertRuleRequest request) {
        long existing = alertRepository.count();
        if (existing >= maxRules) {
            throw new IllegalStateException("Maximum number of alert rules reached: " + maxRules);
        }

        AlertRule rule = AlertRule.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .category(request.getCategory())
                .threshold(request.getThreshold())
                .windowMinutes(request.getWindowMinutes())
                .createdAt(Instant.now())
                .build();

        AlertRule saved = alertRepository.save(rule);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<AlertEvaluationResult> evaluateAll() {
        Instant now = Instant.now();
        return alertRepository.findAll().stream()
                .map(rule -> evaluate(rule, now))
                .toList();
    }

    private AlertEvaluationResult evaluate(AlertRule rule, Instant now) {
        Instant windowStart = now.minusSeconds((long) rule.getWindowMinutes() * 60L);
        long observed = eventRepository.countByRuleCategoryAndTimestampOnOrAfter(
                rule.getCategory(), windowStart);
        boolean firing = observed >= rule.getThreshold();
        return new AlertEvaluationResult(
                rule.getId(),
                rule.getName(),
                rule.getCategory(),
                rule.getThreshold(),
                rule.getWindowMinutes(),
                observed,
                firing,
                now
        );
    }

    private AlertRuleResponse toResponse(AlertRule rule) {
        return new AlertRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getCategory(),
                rule.getThreshold(),
                rule.getWindowMinutes(),
                rule.getCreatedAt()
        );
    }
}
