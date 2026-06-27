package com.akamai.miniwsa.enrichment;

import java.time.Instant;

public interface RepeatOffenderCache {
    boolean isRepeatOffender(String clientIp, Instant eventTime);
    void record(String clientIp, Instant eventTime);
}
