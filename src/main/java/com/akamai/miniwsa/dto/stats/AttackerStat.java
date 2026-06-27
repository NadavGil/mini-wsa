package com.akamai.miniwsa.dto.stats;

public record AttackerStat(String clientIp, long count, double avgThreatScore) {}
