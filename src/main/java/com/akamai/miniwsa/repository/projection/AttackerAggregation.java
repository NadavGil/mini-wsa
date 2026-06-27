package com.akamai.miniwsa.repository.projection;

public interface AttackerAggregation {
    String getClientIp();
    long getCount();
    double getAvgThreatScore();
}
