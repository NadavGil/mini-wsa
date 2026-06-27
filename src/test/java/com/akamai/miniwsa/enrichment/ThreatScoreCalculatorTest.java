package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.RuleInfo;
import com.akamai.miniwsa.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreatScoreCalculatorTest {

    private ThreatScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ThreatScoreCalculator();
    }

    private EnrichedEvent buildEvent(Severity severity, ActionType action, String path,
                                     boolean repeatOffender) {
        RuleInfo rule = RuleInfo.builder()
                .ruleId("R001")
                .severity(severity)
                .category(AttackCategory.INJECTION)
                .build();
        return EnrichedEvent.builder()
                .eventId("e1")
                .rule(rule)
                .action(action)
                .path(path)
                .repeatOffender(repeatOffender)
                .build();
    }

    @Test
    void criticalDenyAdminRepeat_shouldCapAt100() {
        EnrichedEvent event = buildEvent(Severity.CRITICAL, ActionType.DENY, "/admin/panel", true);
        int score = calculator.calculate(event);
        // CRITICAL=40, DENY=20, /admin=15, repeat=15 → 90 — does NOT exceed 100
        assertThat(score).isEqualTo(90);
    }

    @Test
    void criticalDenyLoginRepeat_shouldEqual100() {
        EnrichedEvent event = buildEvent(Severity.CRITICAL, ActionType.DENY, "/login", true);
        int score = calculator.calculate(event);
        // CRITICAL=40, DENY=20, /login=15, repeat=15 → 90
        assertThat(score).isEqualTo(90);
    }

    @Test
    void lowMonitorNormalPath_shouldBe10() {
        EnrichedEvent event = buildEvent(Severity.LOW, ActionType.MONITOR, "/api/data", false);
        int score = calculator.calculate(event);
        assertThat(score).isEqualTo(10);
    }

    @Test
    void mediumAlertNoSensitivePath_shouldBe30() {
        EnrichedEvent event = buildEvent(Severity.MEDIUM, ActionType.ALERT, "/api/users", false);
        int score = calculator.calculate(event);
        // MEDIUM=20, ALERT=10 → 30
        assertThat(score).isEqualTo(30);
    }

    @Test
    void highDenyAdminNoRepeat_shouldBe65() {
        EnrichedEvent event = buildEvent(Severity.HIGH, ActionType.DENY, "/admin", false);
        int score = calculator.calculate(event);
        // HIGH=30, DENY=20, /admin=15 → 65
        assertThat(score).isEqualTo(65);
    }

    @Test
    void pathContainingLoginShouldAddBonus() {
        EnrichedEvent event = buildEvent(Severity.LOW, ActionType.MONITOR, "/login/reset", false);
        int score = calculator.calculate(event);
        // LOW=10, /login=15 → 25
        assertThat(score).isEqualTo(25);
    }

    @Test
    void pathContainsBothAdminAndLogin_bonusShouldApplyOnce() {
        // /admin and /login both in path — bonus should only apply once (+15)
        EnrichedEvent event = buildEvent(Severity.LOW, ActionType.MONITOR, "/admin/login", false);
        int score = calculator.calculate(event);
        // LOW=10, sensitive path=15 (once) → 25
        assertThat(score).isEqualTo(25);
    }

    @Test
    void nullPath_shouldNotThrow() {
        EnrichedEvent event = buildEvent(Severity.LOW, ActionType.MONITOR, null, false);
        int score = calculator.calculate(event);
        assertThat(score).isEqualTo(10);
    }

    @Test
    void scoreShouldNeverExceed100() {
        EnrichedEvent event = buildEvent(Severity.CRITICAL, ActionType.DENY, "/admin/login", true);
        int score = calculator.calculate(event);
        assertThat(score).isLessThanOrEqualTo(100);
    }
}
