package com.akamai.miniwsa.web;

import com.akamai.miniwsa.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.service.StatsService;
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
public class StatsController {

    private final StatsService statsService;

    /**
     * GET /v1/stats/summary?configId=&from=&to=
     */
    @GetMapping("/summary")
    public StatsSummaryResponse summary(
            @RequestParam(required = false) Long configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return statsService.summary(configId, from, to);
    }
}
