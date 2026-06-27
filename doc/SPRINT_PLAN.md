# SPRINT_PLAN.md — Mini WSA Project

## Sprint Summary Table

| Sprint | Git Tag | Stories | Assignees | Goal |
|--------|---------|---------|-----------|------|
| Sprint 1 | v0.1-ingestion | US-01, US-02, US-03 | Senior + Junior | Domain entities, DTOs, Boot config |
| Sprint 2 | v0.2-enrichment | US-04, US-05 | Senior | Repository queries + RepeatOffenderCache |
| Sprint 3 | v0.3-enrichment | US-06, US-07 | Senior | Enrichment pipeline (mapper, calculator, pipeline) |
| Sprint 4 | v0.4-ingestion | US-08 | Senior | Ingest service + controller (all validations) |
| Sprint 5 | v0.5-alerts | US-09, US-10, US-11, US-13 | Senior + Junior | Stats, samples, alerts, error handling |
| Sprint 6 | v0.6-generator | US-12, US-14 | Senior + Junior | Data generator + security hardening |
| Sprint 7 | v0.7-tests | US-15, US-16, US-17 | Senior + Junior | Tests, Docker, README |

---

## US-01: Domain Entities
- **Assignee**: Senior | **Sprint**: v0.1
- **Story**: As a developer, I need JPA-mapped domain entities so that the data model is established before any service logic.
- **Acceptance Criteria**: EnrichedEvent @Entity with all fields; @Embedded RuleInfo and GeoLocation; AlertRule @Entity; Severity/ActionType/AttackCategory enums; table indexes on timestamp, configId+timestamp, clientIp+timestamp, rule_category
- **Git Checkpoint**: Part of `v0.1-ingestion`
- **QA/Security Items**: Verify column constraints; no sensitive fields nullable

---

## US-02: DTO Classes with Bean Validation
- **Assignee**: Junior | **Sprint**: v0.1
- **Story**: As a developer, I need request/response DTOs with Bean Validation so invalid input is rejected before service logic.
- **Acceptance Criteria**: EventIngestRequest with @NotBlank/@NotNull/@Size/@Min/@Max on all fields; @Size(max=2048) path; all response DTOs as Java records; ApiError with FieldViolation nested record; Jackson ISO-8601 Instant serialization
- **Git Checkpoint**: Part of `v0.1-ingestion`
- **QA/Security Items**: @Size bounds on ALL free-text fields

---

## US-03: Spring Boot Main + Config Files
- **Assignee**: Junior | **Sprint**: v0.1
- **Story**: As a developer, I need a runnable Spring Boot application with secure configuration.
- **Acceptance Criteria**: MiniWsaApplication.java; application.yml (h2 default, Jackson UTC); application-h2.yml (H2 console DISABLED); application-postgres.yml (env vars only, no defaults); server.error.include-stacktrace=never; actuator health-only; CacheConfig profile wiring; mvnw wrapper
- **Git Checkpoint**: `v0.1-ingestion`
- **QA/Security Items**: SEC — H2 console off, stacktrace off, actuator restricted

---

## US-04: EventRepository @Query Methods + Projections
- **Assignee**: Senior | **Sprint**: v0.2
- **Story**: As a developer, I need all custom JPQL repository methods so services can query aggregated stats efficiently.
- **Acceptance Criteria**: countByClientIpAndTimestampAfter; countInWindow (optional configId); aggregateByCategory; aggregateByAction; topAttackers(Pageable); topPaths(Pageable); findSamples(all optional filters, Pageable); countSamples; countByRuleCategoryAndTimestampAfter; all 4 projection interfaces; ALL queries use named parameters — no string concatenation
- **Git Checkpoint**: `v0.2-enrichment`
- **QA/Security Items**: Named params verified — no JPQL injection possible

---

## US-05: RepeatOffenderCache (Bounded, TOCTOU-Safe)
- **Assignee**: Senior | **Sprint**: v0.2
- **Story**: As a developer, I need a thread-safe bounded cache so the enrichment pipeline flags repeat IPs without race conditions or OOM.
- **Acceptance Criteria**: RepeatOffenderCache interface; InMemoryRepeatOffenderCache with ConcurrentHashMap<String, Deque<Instant>>; max 10,000 IP entries with LRU eviction; TOCTOU fix via synchronized per-IP deque; 10-min TTL pruning per access; @Scheduled(fixedRate=60000) global prune; JpaRepeatOffenderCache for @Profile("postgres")
- **Git Checkpoint**: `v0.2-enrichment`
- **QA/Security Items**: QA-6 capped eviction; QA-8 TOCTOU concurrent stress test

---

## US-06: AttackTypeMapper + ThreatScoreCalculator
- **Assignee**: Senior | **Sprint**: v0.3
- **Story**: As a developer, I need classification and scoring components so each event gets an attackType and bounded threat score.
- **Acceptance Criteria**: AttackTypeMapper maps all 7 categories; null/unknown → "Unknown"; ThreatScoreCalculator exact formula (CRITICAL=40/HIGH=30/MEDIUM=20/LOW=10 + DENY+20/ALERT+10 + path anyMatch +15 + repeatOffender +15); null safety throughout; min(score,100); path uses anyMatch — +15 once even if both /admin and /login present; injected Clock for testability
- **Git Checkpoint**: Part of `v0.3-enrichment`
- **QA/Security Items**: QA-9 anyMatch; QA-10 null path NPE fix

---

## US-07: EnrichmentPipeline
- **Assignee**: Senior | **Sprint**: v0.3
- **Story**: As a developer, I need an enrichment pipeline orchestrating mapper, calculator, and cache so events are fully enriched before persistence.
- **Acceptance Criteria**: EnrichmentPipeline.enrich(); check repeat-offender BEFORE recording; cache.record() called via @TransactionalEventListener(phase=AFTER_COMMIT); idempotent
- **Git Checkpoint**: `v0.3-enrichment`
- **QA/Security Items**: QA-7 — cache NOT updated on transaction rollback

---

## US-08: EventIngestionService + EventController (Ingest)
- **Assignee**: Senior | **Sprint**: v0.4
- **Story**: As a WAF edge node, I want POST /v1/events/ingest so events enter the analytics pipeline reliably with clear error semantics.
- **Acceptance Criteria**: Accepts single OR array (JsonNode detection); batch > 500 → 413; duplicate eventId → 409; timestamp > now+5min → 422; valid → 201 {ingested:N, eventIds:[...]}; rule.message and path: strip \\r\\n before logging; path: validate no HTML chars (<>\"'); @Transactional saveAll
- **Git Checkpoint**: `v0.4-ingestion`
- **QA/Security Items**: QA-1,2,3; SEC-1 XSS; SEC-2 log injection

---

## US-09: StatsService + StatsController
- **Assignee**: Senior | **Sprint**: v0.5
- **Story**: As an analyst, I want GET /v1/stats/summary to return event aggregations for a time range.
- **Acceptance Criteria**: from > to → 400; range > 90 days → 400; missing configId → all configs; missing from/to → now-24h/now defaults; 5 queries wrapped in @Transactional(readOnly=true); returns totalEvents, byCategory, byAction, topAttackers(10), topTargetedPaths(10)
- **Git Checkpoint**: Part of `v0.5-alerts`
- **QA/Security Items**: QA-11 atomic; QA-14 from>to validation

---

## US-10: SamplesService + Samples Endpoint
- **Assignee**: Junior | **Sprint**: v0.5
- **Story**: As an analyst, I want GET /v1/events/samples to return paginated filtered events.
- **Acceptance Criteria**: All params optional; no params at all → require at least configId or time range (else 400); limit default=20 max=100; limit>100 or limit<1 → 400; offset>=0; sorted by timestamp desc; returns {total, limit, offset, events:[...]}
- **Git Checkpoint**: Part of `v0.5-alerts`
- **QA/Security Items**: QA-12 require filter; limit cap enforced

---

## US-11: AlertService + AlertController
- **Assignee**: Junior | **Sprint**: v0.5
- **Story**: As an operator, I want to define alert rules and evaluate them via REST.
- **Acceptance Criteria**: POST /v1/alerts/define → 201; @Min(1) on threshold AND windowMinutes; GET /v1/alerts/evaluate → List<AlertEvaluationResult>; firing = count >= threshold; max 100 rules enforced (400 if exceeded)
- **Git Checkpoint**: `v0.5-alerts`
- **QA/Security Items**: QA-13 windowMinutes @Min(1)

---

## US-12: DataGeneratorService + DataGenController
- **Assignee**: Junior | **Sprint**: v0.6
- **Story**: As a developer, I want a dev-only POST /v1/dev/generate endpoint to seed realistic test data.
- **Acceptance Criteria**: @Profile("dev") controller; configurable count/waveCount/waveSize; attack waves (1 IP × waveSize events in 30s → trips repeat-offender); routes through EventIngestionService; returns GenerationSummary; 404 in non-dev profile
- **Git Checkpoint**: Part of `v0.6-generator`
- **QA/Security Items**: Verify 404 in postgres/prod profile

---

## US-13: GlobalExceptionHandler
- **Assignee**: Junior | **Sprint**: v0.5
- **Story**: As a client, I need consistent error responses with no internal detail leakage.
- **Acceptance Criteria**: @RestControllerAdvice; MethodArgumentNotValidException → 400 + ApiError with violations; HttpMessageNotReadableException (bad JSON/enum) → 400; DataIntegrityViolationException → 409; Exception fallback → 500 generic message (NO stack trace, NO SQL)
- **Git Checkpoint**: Part of `v0.5-alerts`
- **QA/Security Items**: No `trace` or `exception` field in any error response

---

## US-14: Security Hardening
- **Assignee**: Senior | **Sprint**: v0.6
- **Story**: As a security engineer, I need platform-level hardening so the service resists abuse.
- **Acceptance Criteria**: Bucket4j rate limiting: 1000 req/min on ingest, 100 req/min on stats/samples — 429 on exceed; Tomcat maxPostSize=1MB in application.yml; CORS WebMvcConfigurer with configured allowed origins (no *); all DTO @Size bounds verified; RateLimitingFilter using ConcurrentHashMap<IP, Bucket>
- **Git Checkpoint**: `v0.6-generator`
- **QA/Security Items**: SEC-3,4,8 rate limit burst test; oversized POST rejected

---

## US-15: Dockerfile + docker-compose + README
- **Assignee**: Junior | **Sprint**: v0.7
- **Story**: As a reviewer, I need containerized build and documentation for one-command evaluation.
- **Acceptance Criteria**: Multi-stage Dockerfile (eclipse-temurin:17-jdk-alpine build → 17-jre-alpine runtime); non-root user; docker-compose.yml (postgres:16-alpine + app + healthcheck); README.md (quick start, profiles, all 5 API curl examples, threat score table, git tag roadmap)
- **Git Checkpoint**: Part of `v0.7-tests`
- **QA/Security Items**: Container runs as non-root; no secrets in Dockerfile

---

## US-16: Unit Tests
- **Assignee**: Junior | **Sprint**: v0.7
- **Story**: As a developer, I need unit tests for enrichment and cache logic.
- **Acceptance Criteria**: ThreatScoreCalculatorTest — all severities, all actions, path /admin, /login, both, null, null severity, null action, repeat +15, cap 100; AttackTypeMapperTest — all 7 + null; InMemoryRepeatOffenderCacheTest — 5 not repeat, 6th repeat, outside window excluded, 20-thread CountDownLatch concurrency test; AlertEvaluationTest (Mockito) — firing/not-firing/boundary; all tests: no Spring context
- **Git Checkpoint**: Part of `v0.7-tests`
- **QA/Security Items**: Concurrency test uses CountDownLatch for true simultaneous execution

---

## US-17: Integration Tests
- **Assignee**: Senior | **Sprint**: v0.7
- **Story**: As a developer, I need end-to-end integration tests against H2 verifying all API contracts.
- **Acceptance Criteria**: @SpringBootTest + MockMvc + @ActiveProfiles("h2") + @Transactional; EventIngestionIntegrationTest (201 single, 201 batch, 409 dupe, 422 future ts, 413 large batch, 400 missing field, 400 invalid enum); StatsSummaryIntegrationTest (seed events, verify aggregations, 400 from>to, 400 90-day cap); SamplesIntegrationTest (400 no filter, 200 with filter, pagination, timestamp desc); AlertIntegrationTest (201 define, evaluate firing, 400 windowMinutes=0); ./mvnw test passes
- **Git Checkpoint**: `v0.7-tests`
- **QA/Security Items**: @Transactional rollback per test — no data leakage between tests
