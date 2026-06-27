package com.akamai.miniwsa.dto.stats;

import com.akamai.miniwsa.domain.AttackCategory;

public record CategoryStat(AttackCategory category, long count, double avgThreatScore) {}
