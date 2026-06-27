package com.akamai.miniwsa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleInfo {

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "rule_name")
    private String ruleName;

    @Column(name = "rule_message", length = 1024)
    private String ruleMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_severity")
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_category")
    private AttackCategory category;
}
