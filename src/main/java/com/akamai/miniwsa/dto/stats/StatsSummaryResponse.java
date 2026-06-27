package com.akamai.miniwsa.dto.stats;

import java.util.List;

public record StatsSummaryResponse(
        Long configId,
        long totalEvents,
        List<CategoryStat> byCategory,
        List<ActionStat> byAction,
        List<AttackerStat> topAttackers,
        List<PathStat> topTargetedPaths
) {}
