package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.Severity;
import org.springframework.stereotype.Component;

@Component
public class ThreatScoreCalculator {

    /**
     * Calculates a threat score in the range [0, 100].
     *
     * <pre>
     * severity : CRITICAL=40, HIGH=30, MEDIUM=20, LOW=10, null=0
     * action   : DENY=+20, ALERT=+10, MONITOR=+0, null=0
     * path     : case-insensitive anyMatch of "/admin" or "/login" → +15 (once)
     * repeat   : +15
     * cap      : min(score, 100)  — max theoretical = 40+20+15+15 = 90
     * </pre>
     */
    /** Convenience overload — extracts fields from the entity. */
    public int calculate(EnrichedEvent event) {
        Severity sev = (event.getRule() != null) ? event.getRule().getSeverity() : null;
        return calculate(sev, event.getAction(), event.getPath(), event.isRepeatOffender());
    }

    public int calculate(Severity severity, ActionType action, String path, boolean repeatOffender) {
        int score = 0;

        if (severity != null) {
            score += switch (severity) {
                case CRITICAL -> 40;
                case HIGH     -> 30;
                case MEDIUM   -> 20;
                case LOW      -> 10;
            };
        }

        if (action != null) {
            score += switch (action) {
                case DENY    -> 20;
                case ALERT   -> 10;
                case MONITOR -> 0;
            };
        }

        // Path heuristic: +15 applied at most ONCE using anyMatch
        if (path != null) {
            String lowerPath = path.toLowerCase();
            if (lowerPath.contains("/admin") || lowerPath.contains("/login")) {
                score += 15;
            }
        }

        if (repeatOffender) {
            score += 15;
        }

        return Math.min(score, 100);
    }
}
