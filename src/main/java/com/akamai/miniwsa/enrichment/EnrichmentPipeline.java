package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.EnrichedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Two-phase enrichment pipeline:
 *
 * <ol>
 *   <li>{@link #enrichWithoutRecording} — pure computation, no cache side-effects.
 *       Safe to call before the DB write; a rollback leaves the cache unaffected.</li>
 *   <li>{@link #recordInCache} — writes the event into the repeat-offender cache.
 *       Must be called ONLY after the DB write has committed successfully.</li>
 * </ol>
 *
 * Splitting these two phases prevents cache poisoning when a batch DB write fails
 * and rolls back: the cache is never updated for events that were not persisted.
 */
@Component
@RequiredArgsConstructor
public class EnrichmentPipeline {

    private final AttackTypeMapper attackTypeMapper;
    private final ThreatScoreCalculator threatScoreCalculator;
    private final RepeatOffenderCache repeatOffenderCache;

    /**
     * Computes and sets enriched fields on the event. No cache writes.
     * Check-before-record: isRepeatOffender() reads the cache but does not modify it,
     * so the current event is NOT counted toward its own repeat-offender window.
     */
    public EnrichedEvent enrichWithoutRecording(EnrichedEvent event) {
        boolean repeat = repeatOffenderCache.isRepeatOffender(
                event.getClientIp(), event.getTimestamp());

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

    /**
     * Records the event timestamp in the repeat-offender cache.
     * Call this AFTER a successful DB commit — never before.
     */
    public void recordInCache(EnrichedEvent event) {
        repeatOffenderCache.record(event.getClientIp(), event.getTimestamp());
    }
}
