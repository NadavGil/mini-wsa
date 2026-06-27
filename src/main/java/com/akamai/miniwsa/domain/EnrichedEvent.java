package com.akamai.miniwsa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
    name = "enriched_event",
    indexes = {
        @Index(name = "idx_enriched_event_timestamp", columnList = "timestamp"),
        @Index(name = "idx_enriched_event_config_timestamp", columnList = "config_id,timestamp"),
        @Index(name = "idx_enriched_event_client_ip_timestamp", columnList = "client_ip,timestamp"),
        @Index(name = "idx_enriched_event_rule_category", columnList = "rule_category")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrichedEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "config_id", nullable = false)
    private Long configId;

    @Column(name = "policy_id")
    private String policyId;

    @Column(name = "client_ip", nullable = false)
    private String clientIp;

    @Column(name = "hostname")
    private String hostname;

    @Column(name = "path", length = 2048)
    private String path;

    @Column(name = "method", length = 10)
    private String method;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Embedded
    private RuleInfo rule;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private ActionType action;

    @Embedded
    private GeoLocation geoLocation;

    @Column(name = "request_size")
    private Long requestSize;

    @Column(name = "response_size")
    private Long responseSize;

    @Column(name = "attack_type", nullable = false)
    private String attackType;

    @Column(name = "threat_score", nullable = false)
    private Integer threatScore;

    @Column(name = "repeat_offender", nullable = false)
    private boolean repeatOffender;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;
}
