-- V1: Initial schema for Mini WAF Security Analytics Pipeline
-- Managed by Flyway. Do NOT use ddl-auto: update in production.

CREATE TABLE IF NOT EXISTS enriched_event (
    event_id        VARCHAR(255)    NOT NULL,
    timestamp       TIMESTAMPTZ     NOT NULL,
    config_id       BIGINT          NOT NULL,
    policy_id       VARCHAR(255),
    client_ip       VARCHAR(255)    NOT NULL,
    hostname        VARCHAR(255),
    path            VARCHAR(2048),
    method          VARCHAR(10),
    status_code     INTEGER,
    user_agent      VARCHAR(512),

    -- Embedded RuleInfo
    rule_id         VARCHAR(255),
    rule_name       VARCHAR(255),
    rule_message    VARCHAR(1024),
    rule_severity   VARCHAR(50),
    rule_category   VARCHAR(50),

    -- Action
    action          VARCHAR(50)     NOT NULL,

    -- Embedded GeoLocation
    geo_country     VARCHAR(3),
    geo_city        VARCHAR(100),

    -- Sizing
    request_size    BIGINT,
    response_size   BIGINT,

    -- Enrichment results
    attack_type     VARCHAR(255)    NOT NULL,
    threat_score    INTEGER         NOT NULL,
    repeat_offender BOOLEAN         NOT NULL,
    received_at     TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_enriched_event PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_enriched_event_timestamp
    ON enriched_event (timestamp);

CREATE INDEX IF NOT EXISTS idx_enriched_event_config_timestamp
    ON enriched_event (config_id, timestamp);

CREATE INDEX IF NOT EXISTS idx_enriched_event_client_ip_timestamp
    ON enriched_event (client_ip, timestamp);

CREATE INDEX IF NOT EXISTS idx_enriched_event_rule_category
    ON enriched_event (rule_category);

-- ---------------------------------------------------------------

CREATE TABLE IF NOT EXISTS alert_rule (
    id              VARCHAR(255)    NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    category        VARCHAR(50)     NOT NULL,
    threshold       INTEGER         NOT NULL,
    window_minutes  INTEGER         NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_alert_rule PRIMARY KEY (id)
);
