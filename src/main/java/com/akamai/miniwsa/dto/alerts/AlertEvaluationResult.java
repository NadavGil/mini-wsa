package com.akamai.miniwsa.dto.alerts;

import com.akamai.miniwsa.domain.AttackCategory;

import java.time.Instant;

public record AlertEvaluationResult(
        String ruleId,
        String name,
        AttackCategory category,
        int threshold,
        int windowMinutes,
        long observedCount,
        boolean firing,
        Instant evaluatedAt
) {}
