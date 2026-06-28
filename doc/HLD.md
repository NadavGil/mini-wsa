# Mini WSA — High-Level Design (HLD)

**Project:** Mini WSA (Mini Web Security Analytics)
**Stack:** Java 17, Spring Boot 3.x, Spring Data JPA
**Document type:** High-Level Design
**Status:** Draft v1.0

---

## 1. Overview

Mini WSA is a backend service that ingests web security events (DLRs — Data Log Records), enriches them with derived threat intelligence, persists them, and exposes analytics over REST. It is the analytical core of a Web Application Firewall (WAF) telemetry pipeline: raw events describing HTTP requests and the rules they triggered flow in, and aggregated security insight flows out.

**Scope.** The service covers ingestion (single and batch), a synchronous enrichment pipeline, storage behind an abstracted Data Access Layer (DAL), statistics and sample query APIs, and a rule-based alerting subsystem. It deliberately excludes authentication, multi-tenancy, and distributed stream processing — these are noted as future work.

**System context.** Upstream WAF/edge nodes (or the bundled data generator) POST events to the ingest endpoint. Downstream, a SOC dashboard or analyst tooling consumes the stats, samples, and alert APIs.

---

## 2. Architecture Diagram

```mermaid
flowchart LR
    Client[Client / WAF Edge / Data Generator]
    API[REST API Layer<br/>Controllers + DTO validation<br/>OpenAPI annotations]
    EXEC[Async Executor<br/>ingestionExecutor<br/>pool 4–16 · queue 1000<br/>CallerRunsPolicy]
    SVC[Service Layer<br/>EventIngestionService<br/>StatsService · AlertService]
    ENR[Enrichment Pipeline<br/>enrichWithoutRecording<br/>afterCommit → recordInCache]
    CACHE[(RepeatOffenderCache<br/>InMemory | Redis sorted sets)]
    REDIS[(Redis 7<br/>wsa:ro:{ip} sorted sets)]
    DAL[DAL<br/>EventRepository<br/>JpaSpecificationExecutor<br/>AlertRepository]
    DB[(H2 dev / PostgreSQL prod<br/>schema managed by Flyway)]
    MDC[RequestIdFilter<br/>MDC requestId<br/>X-Request-Id header]

    Client -->|POST /v1/events/ingest| API
    Client -->|GET /v1/stats, /samples, /alerts| API
    API -->|202 Accepted| Client
    API --> MDC
    API -->|validateTimestamps sync| API
    API -->|fire-and-forget| EXEC
    EXEC --> SVC
    SVC --> ENR
    ENR -->|afterCommit| CACHE
    CACHE -.->|redis.enabled=true| REDIS
    SVC --> DAL
    DAL --> DB
```

---

## 3. Component Descriptions

| Layer | Responsibility |
|---|---|
| **REST API (Controllers)** | HTTP binding, DTO deserialization, bean validation, error mapping. Thin — no business logic. |
| **Service Layer** | Orchestrates use cases: ingest, query stats, evaluate alerts. Coordinates enrichment and persistence in a transaction boundary. |
| **Enrichment Pipeline** | Pure(ish) transformation: maps category → attackType, computes threatScore, sets receivedAt, consults the repeat-offender check. |
| **DAL** | Spring Data JPA interfaces abstracting persistence. The only component aware of the physical store. |
| **RepeatOffenderCache** | Interface for fast "events from this IP in last 10 min" lookups; in-memory default, Redis-swappable. |
| **Storage** | H2 (dev/test) or PostgreSQL (prod), selected by Spring profile. |
| **Data Generator** | Spring component that synthesizes realistic events and attack bursts for demos and load testing. |

---

## 4. Data Flow

1. **Ingest.** Client POSTs a single event or array to `/v1/events/ingest`. The controller validates the DTO synchronously (bean validation + `validateTimestamps()`). If valid, it submits to `ingestionExecutor` (fire-and-forget) and immediately returns `202 Accepted` — the HTTP thread never blocks on the DB write.
2. **Validate.** Bean Validation (`@NotNull`, `@Pattern` for IP format, `@Min/@Max` for statusCode, `@NotBlank` for ruleName) rejects malformed payloads with `400`. Future-timestamp check returns `422` synchronously.
3. **Enrich (two-phase).** `enrichWithoutRecording()` computes `attackType`, `threatScore`, and repeat-offender flag without touching the cache. After the transaction commits, the `afterCommit` hook calls `recordInCache()` — preventing cache state from diverging from DB state on rollback.
4. **Store.** The async worker persists enriched entities via `EventRepository.saveAll` in one transaction. Schema is owned by Flyway (`V1__initial_schema.sql`); Hibernate validates — never generates — DDL.
5. **Query.** `/v1/stats/summary` runs DB-level GROUP BY aggregations; `/v1/events/samples` uses `JpaSpecificationExecutor` for dynamic WHERE; `/v1/alerts/evaluate` batches rules by `windowMinutes`, one `GROUP BY category` query per group.

---

## 5. Domain Model

| Field | Type | Notes |
|---|---|---|
| eventId | String (UUID) | Primary key |
| timestamp | Instant | Event occurrence time (indexed) |
| configId / policyId | String | WAF config & policy refs |
| clientIp | String | Source IP (indexed) |
| hostname / path / method | String | Request target |
| statusCode | int | HTTP response code |
| userAgent | String | Client UA |
| rule | embedded | id, name, message, severity, category |
| action | String | DENY, ALERT, MONITOR |
| geoLocation | embedded | country, city |
| requestSize / responseSize | long | Bytes |
| **attackType** | String | *Enriched* — from rule.category |
| **threatScore** | int (0–100) | *Enriched* |
| **receivedAt** | Instant | *Enriched* — server ingest time |

`rule` and `geoLocation` are JPA `@Embeddable` value objects.

---

## 6. Storage Design

**Default — H2 in-memory.** Zero configuration, ideal for unit/integration tests and local dev. Schema auto-generated via JPA DDL.

**Production — PostgreSQL.** Justified by: (a) **relational aggregations** — efficient `GROUP BY` for the stats API; (b) **time-range indexing** on `timestamp` for windowed queries; (c) wide community support and simple Docker Compose integration.

**Schema sketch.**

```sql
events(
  event_id PK, timestamp, config_id, policy_id, client_ip,
  hostname, path, method, status_code, user_agent,
  rule_id, rule_name, rule_message, rule_severity, rule_category,
  action, geo_country, geo_city,
  request_size, response_size,
  attack_type, threat_score, received_at
)
alert_rules(id PK, name, category, threshold, window_minutes)
```

**Index strategy.** `idx_events_timestamp`, `idx_events_client_ip`, composite `idx_events_ip_timestamp` (accelerates repeat-offender query), `idx_events_config_id`, `idx_events_category`.

---

## 7. IoC / DAL Abstraction

All persistence is reached through interfaces, so the engine is injectable via Spring's IoC container — a client swaps databases by setting `spring.profiles.active`, never by editing code.

| Interface | Role |
|---|---|
| `EventRepository extends JpaRepository<EnrichedEvent, String>, JpaSpecificationExecutor<EnrichedEvent>` | CRUD; GROUP BY stats projections via `@Query`; `countByCategoriesFrom` for batched alert evaluation; `JpaSpecificationExecutor` for dynamic sample filters. |
| `AlertRepository extends JpaRepository<AlertRule, String>` | Alert rule CRUD. |
| `RepeatOffenderCache` | `boolean isRepeatOffender(String ip, Instant t)` / `void record(String ip, Instant t)`; two implementations selected by property. |

**Profile wiring.** `application-h2.yml` and `application-postgres.yml` carry datasource config. `RepeatOffenderCache` wiring uses `@ConditionalOnProperty` in `CacheConfig`: `wsa.cache.redis.enabled=true` activates `RedisRepeatOffenderCache` (sorted sets); `@ConditionalOnMissingBean` falls back to `InMemoryRepeatOffenderCache`. The Javadoc warns that the in-memory impl degrades silently when two or more instances run — Redis must be enabled for production multi-instance deployments.

---

## 8. Enrichment Pipeline

**attackType mapping.**

| rule.category | attackType |
|---|---|
| INJECTION | SQL/Command Injection |
| XSS | Cross-Site Scripting |
| PROTOCOL_VIOLATION | Protocol Anomaly |
| DATA_LEAKAGE | Data Exfiltration |
| BOT | Bot Activity |
| DOS | Denial of Service |
| RATE_LIMIT | Rate Limiting |

**threatScore formula (clamped 0–100):**

```
score  = severityWeight(rule.severity)     // CRITICAL=40, HIGH=30, MEDIUM=20, LOW=10
score += actionWeight(action)              // DENY+20, ALERT+10, MONITOR+0
score += pathHeuristic(path)              // contains /admin or /login → +15
score += repeatOffenderBonus(clientIp)    // >5 events from IP in last 10 min → +15
threatScore = min(100, max(0, score))
```

**Repeat-offender strategy (two-phase).** `enrichWithoutRecording()` checks the cache for prior events from this IP — without writing to it. After the transaction commits durably, the `afterCommit` hook calls `recordInCache()`. This ensures cache state is never ahead of DB state: a DB rollback cannot leave ghost entries in the cache. Two cache backends are available: `InMemoryRepeatOffenderCache` (single-instance dev) and `RedisRepeatOffenderCache` (production, uses Redis sorted sets — `ZADD + ZREMRANGEBYSCORE + ZCOUNT` in O(log N)).

---

## 9. Alerting (Bonus)

**Rule schema:** `{ id, name, category, threshold (N), windowMinutes (Y) }`, stored in `alert_rules` and managed via `POST /v1/alerts/define`.

**Evaluate (`GET /v1/alerts/evaluate`).** For each rule, count events matching `category` within the last `windowMinutes`. If `count > threshold`, the rule is **FIRING**; otherwise **OK**.

---

## 10. Scalability & Performance

- **Async ingestion.** `@Async("ingestionExecutor")` decouples HTTP threads from DB writes. A `ThreadPoolTaskExecutor` (core 4, max 16, queue 1000) handles bursts; `CallerRunsPolicy` provides backpressure instead of dropping events. Returns `202 Accepted` immediately, giving linear throughput scaling.
- **Repeat-offender cache** removes per-event DB round-trip from the hot path. Redis sorted sets (`ZCOUNT`) answer in O(log N); in-memory `ConcurrentHashMap<ip, Deque<Instant>>` is the dev/test fallback.
- **Stats via GROUP BY.** Aggregations execute in the database — never by loading rows into the JVM.
- **Batch ingestion** uses `saveAll` within one transaction to amortize round-trips.
- **N+1 alert queries eliminated.** Rules batched by `windowMinutes`; at most K DB queries where K = distinct window sizes (typically 1–3).
- **Flyway schema management.** `V1__initial_schema.sql` owns DDL; `ddl-auto: validate` in prod means Hibernate never silently mutates the schema.
- **Indexes** on `timestamp` and `(client_ip, timestamp)` keep windowed queries sub-linear.
- Future: Kafka topic replacing thread pool for durable async ingest; table partitioning by time.

---

## 11. Security Considerations

Per the assignment, no authentication is required. Implemented controls: strict **input validation** (bean validation on every DTO field; `clientIp` validated with `@Pattern(^[\d.:a-fA-F]+$)` + `@Size(max=45)`; `ruleName` requires `@NotBlank`; size bounds on all string fields); parameterized JPA queries (no SQL injection); `ruleMessage` sanitized (CR/LF stripped) to prevent log injection; **Bucket4j token-bucket rate limiting** (200 req/min per IP); `RequestIdFilter` injects a UUID request ID into MDC and echoes it as `X-Request-Id` for log correlation; `synchronized AlertService.define()` prevents TOCTOU on rule-count check. **Future hardening:** API-key or OAuth2, per-endpoint rate-limit buckets, PII retention policy for IPs/geo data.

---

## 12. Testability

- **Unit tests** target the enrichment logic (threatScore boundaries, attackType mapping, repeat-offender bonus) — pure functions with no I/O.
- **Mock DAL pattern.** Persistence sits behind interfaces, so services are tested with Mockito mocks, isolating business logic.
- **Integration tests** run against H2 (auto-configured), exercising controller → service → DAL end-to-end.
- **Slice tests** (`@WebMvcTest` for controllers, `@DataJpaTest` for repositories) keep feedback fast.

---

## 13. Git Checkpoint Strategy

| Tag | Milestone |
|---|---|
| `v0.1-ingestion` | Ingest endpoint, DTOs, validation, persistence |
| `v0.2-enrichment` | attackType mapping + threatScore + repeat-offender |
| `v0.3-stats` | `/v1/stats/summary` GROUP BY aggregations |
| `v0.4-samples` | `/v1/events/samples` query API |
| `v0.5-alerts` | Alert define + evaluate (bonus) |
| `v0.6-generator` | Data generator with attack-wave simulation |
| `v0.7-tests` | Unit + integration test suites |
| `v0.8-board-fixes` | Board review hardening: async ingest (202), afterCommit cache, Redis cache, N+1 batch alerts, Flyway migration, MDC filter, OpenAPI, pagination fix, IP validation |

---

## 14. Board Review Summary

The post-delivery board review identified 15 findings across three severity levels. All were resolved in a single hardening sprint.

| Severity | Count | Examples |
|---|---|---|
| **Critical** | 2 | C1: Cache poisoning on rollback; C2: Synchronous ingestion blocking I/O thread |
| **Major** | 7 | JPA INSERT/MERGE, in-memory cache sharding, N+1 alerts, timestamp bypass in async, IP validation, blank ruleName, broken pagination |
| **Minor** | 6 | Dead repository method, Flyway schema management, MDC request ID, AlertService TOCTOU, Micrometer metrics, OpenAPI annotations |

---

*End of document.*
