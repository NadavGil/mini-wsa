package com.akamai.miniwsa.dto.stats;

import com.akamai.miniwsa.domain.ActionType;

public record ActionStat(ActionType action, long count) {}
