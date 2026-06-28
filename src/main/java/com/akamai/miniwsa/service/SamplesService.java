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

    public SamplesPageResponse findSamples(Long configId, Instant from, Instant to,
                                           AttackCategory category, ActionType action,
                                           int limit, int offset) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);

        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }

        int pageNumber = offset / effectiveLimit;

        // Use JPA Criteria Specifications to build a dynamic WHERE clause.
        // This avoids the PostgreSQL "could not determine data type of parameter"
        // error that occurs with (:param is null OR ...) JPQL patterns when
        // Hibernate sends NULL without a type hint for Instant/enum columns.
        Specification<EnrichedEvent> spec = buildSpec(configId, from, to, category, action);

        Page<EnrichedEvent> page = eventRepository.findAll(
                spec,
                PageRequest.of(pageNumber, effectiveLimit, Sort.by("timestamp").descending()));

        List<EventSampleResponse> responses = page.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new SamplesPageResponse(page.getTotalElements(), effectiveLimit, offset, responses);
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
