package com.akamai.miniwsa.dto.samples;

import java.util.List;

public record SamplesPageResponse(long total, int limit, int offset, List<EventSampleResponse> events) {}
