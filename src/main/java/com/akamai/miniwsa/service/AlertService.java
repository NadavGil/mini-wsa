package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.AlertRule;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.dto.alerts.AlertEvaluationResult;
import com.akamai.miniwsa.dto.alerts.AlertRuleRequest;
import com.akamai.miniwsa.dto.alerts.AlertRuleResponse;
import com.akamai.miniwsa.repository.AlertRepository;
import com.akamai.miniwsa.repository.EventRepository;
import com.akamai.miniwsa.repository.projection.CategoryCount;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;
    private final EventRepository eventRepository;

    @Value("${wsa.alerts.max-rules:100}")
    private int maxRules;

    /**
     * Creates an alert rule.
     *
     * <p>The count-then-insert sequence is protected by {@code synchronized} to
     * prevent a TOCTOU race where two concurrent requests both read the same count,
     * both pass the cap check, and both insert — exceeding {@code maxRules}.
     *
     * <p>Single-instance note: {@code synchronized} is sufficient here because we
     * run as a single JVM. In a horizontally-scaled deployment, replace with a
     * database-level constraint (e.g. a CHECK enforced via a trigger, or a
     * {@code SELECT ... FOR UPDATE} on a counter row using pessimistic locking).
     */
    @Transactional
    public synchronized AlertRuleResponse define(AlertRuleRequest request) {
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

    /**
     * Evaluates all alert rules in a single database round-trip per distinct window size.
     *
     * <p>The old implementation issued one COUNT query per rule (N+1). This implementation
     * groups rules by {@code windowMinutes}, then issues a single {@code GROUP BY category}
     * query per group — collapsing N queries to at most K queries where K is the number of
     * distinct window sizes. In practice K ≈ 1–3 for most configurations.
     */
    @Transactional(readOnly = true)
    public List<AlertEvaluationResult> evaluateAll() {
        Instant now = Instant.now();
        List<AlertRule> rules = alertRepository.findAll();

        // Group rules by windowMinutes so we can batch each window in one DB query.
        Map<Integer, List<AlertRule>> byWindow = rules.stream()
                .collect(Collectors.groupingBy(AlertRule::getWindowMinutes));

        // Key: "windowMinutes:CATEGORY" → count.
        // We must include windowMinutes in the key because the same category can appear
        // in rules with different window sizes — their counts differ and must not collide.
        Map<String, Long> observed = new java.util.HashMap<>();
        for (Map.Entry<Integer, List<AlertRule>> entry : byWindow.entrySet()) {
            int windowMinutes = entry.getKey();
            Instant windowStart = now.minusSeconds((long) windowMinutes * 60L);
            List<AttackCategory> categories = entry.getValue().stream()
                    .map(AlertRule::getCategory)
                    .distinct()
                    .toList();
            List<CategoryCount> counts = eventRepository.countByCategoriesFrom(categories, windowStart);
            counts.forEach(c -> observed.put(windowMinutes + ":" + c.getCategory().name(), c.getCount()));
        }

        return rules.stream()
                .map(rule -> {
                    String key = rule.getWindowMinutes() + ":" + rule.getCategory().name();
                    long count = observed.getOrDefault(key, 0L);
                    return new AlertEvaluationResult(
                            rule.getId(),
                            rule.getName(),
                            rule.getCategory(),
                            rule.getThreshold(),
                            rule.getWindowMinutes(),
                            count,
                            count >= rule.getThreshold(),
                            now
                    );
                })
                .toList();
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
