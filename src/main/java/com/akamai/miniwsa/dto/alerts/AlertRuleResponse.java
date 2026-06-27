package com.akamai.miniwsa.dto.alerts;

import com.akamai.miniwsa.domain.AttackCategory;

import java.time.Instant;

public record AlertRuleResponse(
        String id,
        String name,
        AttackCategory category,
        int threshold,
        int windowMinutes,
        Instant createdAt
) {}
