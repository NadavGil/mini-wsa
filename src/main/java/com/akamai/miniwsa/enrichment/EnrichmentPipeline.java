package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.EnrichedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EnrichmentPipeline {

    private final AttackTypeMapper attackTypeMapper;
    private final ThreatScoreCalculator threatScoreCalculator;
    private final RepeatOffenderCache repeatOffenderCache;

    /**
     * Enriches the given event in-place and returns it.
     * <ol>
     *   <li>Check repeat-offender status BEFORE recording the current event (so the
     *       current event is not counted toward its own repeat-offender window).</li>
     *   <li>Record the event in the cache.</li>
     *   <li>Set repeatOffender, attackType, and threatScore fields.</li>
     * </ol>
     */
    public EnrichedEvent enrich(EnrichedEvent event) {
        // 1. Check before recording
        boolean repeat = repeatOffenderCache.isRepeatOffender(event.getClientIp(), event.getTimestamp());

        // 2. Record into cache
        repeatOffenderCache.record(event.getClientIp(), event.getTimestamp());

        // 3. Set enriched fields
        event.setRepeatOffender(repeat);

        if (event.getRule() != null) {
            event.setAttackType(attackTypeMapper.map(event.getRule().getCategory()));
            event.setThreatScore(threatScoreCalculator.calculate(
                    event.getRule().getSeverity(),
                    event.getAction(),
                    event.getPath(),
                    repeat));
        } else {
            event.setAttackType("Unknown");
            event.setThreatScore(0);
        }

        return event;
    }
}
