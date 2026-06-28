package com.akamai.miniwsa.web;

import com.akamai.miniwsa.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/v1/stats")
@RequiredArgsConstructor
@Tag(name = "Stats", description = "Aggregated WAF event statistics")
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/summary")
    @Operation(
        summary = "Stats summary",
        description = "Returns total event count, breakdown by attack category and action type, " +
                      "top attacking IPs, and top targeted paths for a given time window. " +
                      "Omitting configId returns global stats across all customer configs. " +
                      "Default window: last 24 hours."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Aggregated stats summary"),
        @ApiResponse(responseCode = "400", description = "Invalid time range (from must be before to, max 90 days)")
    })
    public StatsSummaryResponse summary(
            @Parameter(description = "Customer config ID — omit for all configs")
                @RequestParam(required = false) Long configId,
            @Parameter(description = "Range start (ISO-8601, default: now minus 24 h)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Range end (ISO-8601, default: now)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return statsService.summary(configId, from, to);
    }
}
