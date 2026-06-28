package com.akamai.miniwsa.service;

import com.akamai.miniwsa.dto.stats.ActionStat;
import com.akamai.miniwsa.dto.stats.AttackerStat;
import com.akamai.miniwsa.dto.stats.CategoryStat;
import com.akamai.miniwsa.dto.stats.PathStat;
import com.akamai.miniwsa.dto.stats.StatsSummaryResponse;
import com.akamai.miniwsa.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

    private final EventRepository eventRepository;

    @Value("${wsa.stats.max-range-days:90}")
    private int maxRangeDays;

    public StatsSummaryResponse summary(Long configId, Instant from, Instant to) {
        Instant effectiveTo = (to != null) ? to : Instant.now();
        Instant effectiveFrom = (from != null) ? from : effectiveTo.minus(Duration.ofHours(24));

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        if (Duration.between(effectiveFrom, effectiveTo).toDays() > maxRangeDays) {
            throw new IllegalArgumentException("Time range exceeds maximum of " + maxRangeDays + " days");
        }

        long total = eventRepository.countInWindow(configId, effectiveFrom, effectiveTo);

        List<CategoryStat> byCategory = eventRepository
                .aggregateByCategory(configId, effectiveFrom, effectiveTo)
                .stream()
                .map(a -> new CategoryStat(a.getCategory(), a.getCount(), a.getAvgThreatScore()))
                .toList();

        List<ActionStat> byAction = eventRepository
                .aggregateByAction(configId, effectiveFrom, effectiveTo)
                .stream()
                .map(a -> new ActionStat(a.getAction(), a.getCount()))
                .toList();

        List<AttackerStat> topAttackers = eventRepository
                .topAttackers(configId, effectiveFrom, effectiveTo, PageRequest.of(0, 10))
                .stream()
                .map(a -> new AttackerStat(a.getClientIp(), a.getCount(), a.getAvgThreatScore()))
                .toList();

        List<PathStat> topPaths = eventRepository
                .topPaths(configId, effectiveFrom, effectiveTo, PageRequest.of(0, 10))
                .stream()
                .map(a -> new PathStat(a.getPath(), a.getCount()))
                .toList();

        return new StatsSummaryResponse(configId, total, byCategory, byAction, topAttackers, topPaths);
    }
}
