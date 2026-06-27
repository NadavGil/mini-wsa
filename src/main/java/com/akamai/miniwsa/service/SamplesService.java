package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.dto.samples.EventSampleResponse;
import com.akamai.miniwsa.dto.samples.SamplesPageResponse;
import com.akamai.miniwsa.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SamplesService {

    private final EventRepository eventRepository;

    public SamplesPageResponse findSamples(Long configId, Instant from, Instant to,
                                           AttackCategory category, ActionType action,
                                           int limit, int offset) {
        // Clamp limit: 1-100
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);

        // Enforce offset must be a non-negative multiple of effectiveLimit.
        // Integer-division truncation (e.g. offset=5, limit=20 → page 0, wrong rows)
        // would silently return wrong data; reject with a clear error instead.
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (offset % effectiveLimit != 0) {
            throw new IllegalArgumentException(
                    "offset (" + offset + ") must be a multiple of limit (" + effectiveLimit + ")");
        }
        int pageNumber = offset / effectiveLimit;

        List<EnrichedEvent> events = eventRepository.findSamples(
                configId, from, to, category, action,
                PageRequest.of(pageNumber, effectiveLimit));

        long total = eventRepository.countSamples(configId, from, to, category, action);

        List<EventSampleResponse> responses = events.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new SamplesPageResponse(total, effectiveLimit, offset, responses);
    }

    private EventSampleResponse toResponse(EnrichedEvent e) {
        String country = (e.getGeoLocation() != null) ? e.getGeoLocation().getCountry() : null;
        AttackCategory category = (e.getRule() != null) ? e.getRule().getCategory() : null;
        return new EventSampleResponse(
                e.getEventId(),
                e.getTimestamp(),
                e.getConfigId(),
                e.getClientIp(),
                e.getPath(),
                e.getMethod(),
                e.getStatusCode(),
                category,
                e.getAction(),
                e.getAttackType(),
                e.getThreatScore(),
                e.isRepeatOffender(),
                country
        );
    }
}
