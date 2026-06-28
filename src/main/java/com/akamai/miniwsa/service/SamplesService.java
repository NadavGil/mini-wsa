package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.dto.samples.EventSampleResponse;
import com.akamai.miniwsa.dto.samples.SamplesPageResponse;
import com.akamai.miniwsa.repository.EventRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SamplesService {

    private final EventRepository eventRepository;

    /**
     * Returns a page of matching events.
     *
     * <p>Uses 0-indexed page-number semantics: {@code page=0} is the first
     * {@code limit} rows, {@code page=1} is the next, and so on. This maps
     * directly to JPA {@code PageRequest} without the broken integer-division
     * that the old {@code offset} parameter introduced (e.g. offset=25, limit=20
     * would return rows 20–39 instead of the intended 25–44).
     *
     * <p>Production note: at tens of millions of rows, deep pages force a full
     * index scan. The right solution at scale is keyset pagination
     * ({@code WHERE timestamp < :cursor}), which this service can adopt by
     * adding an optional {@code before} cursor param — no contract break.
     */
    public SamplesPageResponse findSamples(Long configId, Instant from, Instant to,
                                           AttackCategory category, ActionType action,
                                           int limit, int page) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);

        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }

        // Use JPA Criteria Specifications to build a dynamic WHERE clause.
        // This avoids the PostgreSQL "could not determine data type of parameter"
        // error that occurs with (:param is null OR ...) JPQL patterns when
        // Hibernate sends NULL without a type hint for Instant/enum columns.
        Specification<EnrichedEvent> spec = buildSpec(configId, from, to, category, action);

        Page<EnrichedEvent> pageResult = eventRepository.findAll(
                spec,
                PageRequest.of(page, effectiveLimit, Sort.by("timestamp").descending()));

        List<EventSampleResponse> responses = pageResult.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new SamplesPageResponse(pageResult.getTotalElements(), effectiveLimit, page, responses);
    }

    private Specification<EnrichedEvent> buildSpec(Long configId, Instant from, Instant to,
                                                    AttackCategory category, ActionType action) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (configId != null) {
                predicates.add(cb.equal(root.get("configId"), configId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("rule").get("category"), category));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private EventSampleResponse toResponse(EnrichedEvent e) {
        String country = (e.getGeoLocation() != null) ? e.getGeoLocation().getCountry() : null;
        AttackCategory cat = (e.getRule() != null) ? e.getRule().getCategory() : null;
        return new EventSampleResponse(
                e.getEventId(),
                e.getTimestamp(),
                e.getConfigId(),
                e.getClientIp(),
                e.getPath(),
                e.getMethod(),
                e.getStatusCode(),
                cat,
                e.getAction(),
                e.getAttackType(),
                e.getThreatScore(),
                e.isRepeatOffender(),
                country
        );
    }
}
