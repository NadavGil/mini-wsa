# Mini WSA — Sprint Plan

**Project**: Mini Security Analytics Pipeline  
**Tech Stack**: Java 17, Spring Boot 3.2, Spring Data JPA, H2/PostgreSQL, Bucket4j  
**Team**: 1 Senior Developer, 1 Junior Developer  
**Sprints**: 7 (v0.1 – v0.7)

---

## Section 1: Sprint Overview

| Sprint | Tag  | Goal |
|--------|------|------|
| 1 | v0.1 | Foundation: domain model, enums, DTOs with validation, and project skeleton |
| 2 | v0.2 | Data layer: repositories, cache interface, and app/config wiring |
| 3 | v0.3 | Enrichment core: mapper, scorer, pipeline |
| 4 | v0.4 | Ingest endpoint: ingestion service, controller, error handling |
| 5 | v0.5 | Query layer: stats, samples, alert CRUD |
| 6 | v0.6 | Security hardening, rate limiting, dev tooling |
| 7 | v0.7 | Tests, Dockerfile, docker-compose, README |

---

## Section 2: User Stories

---

## US-1: Domain Entities and Enums
- **Assignee**: Senior
- **Sprint**: v0.1
- **Story**: As a developer, I want JPA-mapped domain classes so that the data model is defined before any service logic is written.
- **Acceptance Criteria**:
  - `EnrichedEvent` is a `@Entity` with all fields mapped; embeds `RuleInfo` and `GeoLocation`
  - `AlertRule` is a `@Entity` with `@Min(1)` on `windowMinutes`
  - `Severity`, `ActionType`, and `AttackCategory` enums exist and are referenced by entities
  - Schema DDL can be generated from entities without errors
- **Git Checkpoint**: v0.1-domain
- **QA/Security Items**: QA-13 — `windowMinutes` must carry `@Min(1)` at the entity level

---

## US-2: DTO Classes with Bean Validation
- **Assignee**: Junior
- **Sprint**: v0.1
- **Story**: As an API consumer, I want well-validated request and response DTOs so that bad input is rejected before it reaches service logic.
- **Acceptance Criteria**:
  - `EventIngestRequest` has `@NotBlank` on `clientIp`, `@Size(max=2048)` on `path`, `@NotNull` on `timestamp`
  - All response DTOs (`EnrichedEventResponse`, `StatsSummaryResponse`, `SampleResponse`, `AlertRuleResponse`) are immutable records or final classes
  - `ApiError` carries `status`, `message`, and `timestamp` fields
  - Bean validation annotations compile and pass a basic unit test
- **Git Checkpoint**: v0.1-dtos
- **QA/Security Items**: QA-4 (null clientIp), QA-5 (path > 2048), QA-3 (future timestamp — @Future or custom validator)

---

## US-3: Spring Boot Main and Configuration Files
- **Assignee**: Junior
- **Sprint**: v0.1
- **Story**: As a developer, I want the application to start with a clean configuration so that all profiles, caches, and datasources are wired before feature work begins.
- **Acceptance Criteria**:
  - `application.yml` sets `server.error.include-stacktrace=never` and exposes only `health,info` actuator endpoints
  - `application-dev.yml` enables H2 console; base profile disables it
  - `application-postgres.yml` binds datasource to env vars; app fails to start if vars are absent
  - `CacheConfig` registers a Caffeine-backed cache manager with a bounded spec
- **Git Checkpoint**: v0.1-config
- **QA/Security Items**: SEC-5 (H2 console), SEC-6 (stacktrace), SEC-7 (actuator), SEC-9 (DB credentials)

---

## US-4: EventRepository with Query Methods and Projections
- **Assignee**: Senior
- **Sprint**: v0.2
- **Story**: As a developer, I want a repository layer with all necessary JPQL queries so that services can retrieve event data without writing raw SQL.
- **Acceptance Criteria**:
  - `EventRepository` extends `JpaRepository<EnrichedEvent, UUID>`
  - Named queries cover: find by `clientIp` in window, severity aggregation, top-N sample retrieval, and existence check by `eventId`
  - Projection interfaces (`StatsSummaryProjection`, `SampleProjection`) compile and are used in query return types
  - All multi-row queries are annotated `@Transactional(readOnly=true)` at the repo or service call site
- **Git Checkpoint**: v0.2-repository
- **QA/Security Items**: QA-11 (non-atomic stats queries), QA-12 (samples must require a filter)

---

## US-5: RepeatOffenderCache Interface and In-Memory Implementation
- **Assignee**: Senior
- **Sprint**: v0.2
- **Story**: As a system, I want a bounded, thread-safe repeat-offender cache so that high-frequency IPs are flagged without unbounded memory growth.
- **Acceptance Criteria**:
  - `RepeatOffenderCache` interface exposes `record(ip, timestamp)` and `isRepeatOffender(ip)` methods
  - `InMemoryRepeatOffenderCache` uses a per-IP `synchronized` deque (or striped lock) — no TOCTOU window
  - Total map size is capped via Caffeine or an LRU eviction strategy; no unbounded `ConcurrentHashMap`
  - Unit test verifies concurrent writes from two threads produce a consistent offender count
- **Git Checkpoint**: v0.2-cache
- **QA/Security Items**: QA-6 (unbounded cache), QA-8 (TOCTOU race)

---

## US-6: AttackTypeMapper and ThreatScoreCalculator
- **Assignee**: Senior
- **Sprint**: v0.3
- **Story**: As an analyst, I want accurate attack classification and threat scoring so that enriched events carry reliable risk signals.
- **Acceptance Criteria**:
  - `AttackTypeMapper` returns `UNKNOWN` (not null) for unrecognized patterns
  - Path keyword heuristic uses `anyMatch` — a path containing both `/admin` and `/login` scores the bonus only once
  - `ThreatScoreCalculator` handles null path without throwing `NullPointerException`
  - All score boundary conditions (min score 0, max score 100 clamp) are exercised in unit tests
- **Git Checkpoint**: v0.3-enrichment-core
- **QA/Security Items**: QA-9 (anyMatch bonus), QA-10 (null path NPE)

---

## US-7: EnrichmentPipeline
- **Assignee**: Senior
- **Sprint**: v0.3
- **Story**: As a developer, I want a single orchestration class so that mapper, scorer, and cache are composed in a defined order with clear error boundaries.
- **Acceptance Criteria**:
  - `EnrichmentPipeline.enrich(EventIngestRequest)` returns a fully populated `EnrichedEvent`
  - Cache is updated only after the event has been persisted (enforced by calling `cache.record()` from within the same `@Transactional` commit phase or after successful save — see QA-7)
  - Pipeline is `@Component` and injectable; no static references
- **Git Checkpoint**: v0.3-pipeline
- **QA/Security Items**: QA-7 (cache update after DB commit)

---

## US-8: EventIngestionService and Ingest Controller
- **Assignee**: Senior
- **Sprint**: v0.4
- **Story**: As an API consumer, I want a batch ingest endpoint that validates, enriches, and persists events so that security data flows into the system reliably.
- **Acceptance Criteria**:
  - `POST /api/v1/events` accepts a list; returns HTTP 413 if batch size > 500
  - Duplicate `eventId` returns HTTP 409; no 500 stack trace exposed
  - Event timestamp more than 5 minutes in the future returns HTTP 422
  - `path` field is sanitized on ingest: HTML characters (`<`, `>`, `"`, `'`) cause HTTP 422 rejection
  - `rule.message` field has newlines stripped before any log statement
- **Git Checkpoint**: v0.4-ingest
- **QA/Security Items**: QA-1 (413 batch cap), QA-2 (409 dupe), QA-3 (422 future ts), SEC-1 (path XSS), SEC-2 (log injection)

---

## US-9: StatsService and StatsController
- **Assignee**: Senior
- **Sprint**: v0.5
- **Story**: As an analyst, I want a stats summary endpoint so that I can retrieve aggregated threat metrics for a time window.
- **Acceptance Criteria**:
  - `GET /api/v1/stats` requires `from` and `to`; returns HTTP 400 if `from > to`
  - Service method is wrapped in `@Transactional(readOnly=true)` to make all reads atomic
  - Time range is capped to a configurable maximum (default 30 days); longer range returns HTTP 422
- **Git Checkpoint**: v0.5-stats
- **QA/Security Items**: QA-11 (@Transactional readOnly), QA-14 (from > to validation)

---

## US-10: SamplesService and Samples Endpoint
- **Assignee**: Junior
- **Sprint**: v0.5
- **Story**: As an analyst, I want to retrieve sample events so that I can inspect raw enriched data for specific configs or time windows.
- **Acceptance Criteria**:
  - `GET /api/v1/samples` requires at least one of `configId` or a time range; missing both returns HTTP 400
  - Result count is capped at a configurable max (default 200); requests above cap are silently clamped or return HTTP 422
  - Response uses `SampleProjection` — no full entity serialization
- **Git Checkpoint**: v0.5-samples
- **QA/Security Items**: QA-12 (require filter on samples)

---

## US-11: AlertRule CRUD, AlertService, and AlertController
- **Assignee**: Junior
- **Sprint**: v0.5
- **Story**: As an operator, I want to manage alert rules so that the system can trigger notifications based on configurable threat thresholds.
- **Acceptance Criteria**:
  - Full CRUD endpoints for `AlertRule` under `/api/v1/alerts`
  - `windowMinutes` field validated with `@Min(1)` — zero or negative value returns HTTP 422
  - `AlertService.evaluate()` queries events within the rule window and compares count against threshold
  - Creating a rule with a duplicate name returns HTTP 409
- **Git Checkpoint**: v0.5-alerts
- **QA/Security Items**: QA-13 (windowMinutes @Min(1))

---

## US-12: GlobalExceptionHandler
- **Assignee**: Junior
- **Sprint**: v0.5
- **Story**: As an API consumer, I want consistent, structured error responses so that all failure modes return `ApiError` JSON rather than default Spring error bodies.
- **Acceptance Criteria**:
  - `@RestControllerAdvice` handles: `MethodArgumentNotValidException` → 400, `ConstraintViolationException` → 422, `DataIntegrityViolationException` → 409, `PayloadTooLargeException` → 413, and all unhandled exceptions → 500
  - Response body is always `ApiError`; no raw stack traces
  - Integrated test verifies each mapping fires correctly
- **Git Checkpoint**: v0.5-error-handling

---

## US-13: DataGeneratorService and Dev Controller
- **Assignee**: Junior
- **Sprint**: v0.6
- **Story**: As a developer, I want a dev-only data generation endpoint so that demo and manual testing scenarios can be seeded quickly.
- **Acceptance Criteria**:
  - `DataGeneratorService` and `DataGenController` are annotated `@Profile("dev")` — they do not load in prod or test profiles
  - `POST /dev/generate?count=N` persists N synthetic events (max 1000)
  - Data covers all severity levels and attack categories for realistic demo output
- **Git Checkpoint**: v0.6-devtools

---

## US-14: Security Hardening
- **Assignee**: Senior
- **Sprint**: v0.6
- **Story**: As a security engineer, I want rate limiting, CORS policy, and hardened defaults so that the API surface area is minimized in production.
- **Acceptance Criteria**:
  - Bucket4j rate limiter applied to all endpoints (configurable limit, default 100 req/min per IP); excess returns HTTP 429
  - CORS configured explicitly — no wildcard `*` in production profile; allowed origins sourced from config
  - `server.tomcat.max-http-form-post-size` limits raw request body size before deserialization
  - `spring.h2.console.enabled=false` in base `application.yml`; true only in `application-dev.yml`
  - All settings verified by a Spring Boot integration test that boots with the prod profile
- **Git Checkpoint**: v0.6-security
- **QA/Security Items**: SEC-3 (rate limiting), SEC-4 (Tomcat maxPostSize), SEC-5 (H2 console), SEC-8 (CORS)

---

## US-15: Unit Tests
- **Assignee**: Junior (scaffolding) + Senior (boundary and concurrency cases)
- **Sprint**: v0.7
- **Story**: As a team, we want a comprehensive unit test suite so that core logic regressions are caught without a running database.
- **Acceptance Criteria**:
  - `ThreatScoreCalculatorTest` covers: null path, min/max clamp, repeat-offender bonus, all attack category branches
  - `AttackTypeMapperTest` covers: known patterns, unknown pattern returns `UNKNOWN`, null input
  - `InMemoryRepeatOffenderCacheTest` runs two threads concurrently and asserts no data races (use `CountDownLatch`)
  - `AlertEvaluationTest` covers threshold-met and threshold-not-met cases
  - All tests use JUnit 5 + AssertJ; no Spring context required
- **Git Checkpoint**: v0.7-unit-tests

---

## US-16: Integration Tests
- **Assignee**: Senior
- **Sprint**: v0.7
- **Story**: As a team, we want end-to-end integration tests so that the full HTTP→service→DB flow is verified before release.
- **Acceptance Criteria**:
  - `EventIngestionIntegrationTest`: batch cap (413), duplicate (409), future timestamp (422), valid batch persists and returns 201
  - `StatsSummaryIntegrationTest`: valid range returns 200 with correct aggregates; `from > to` returns 400
  - `SamplesIntegrationTest`: no filter returns 400; valid filter returns paginated results
  - `AlertIntegrationTest`: CRUD lifecycle, windowMinutes=0 returns 422, evaluation triggers on threshold
  - All tests use `@SpringBootTest` with H2 and `@Transactional` rollback
- **Git Checkpoint**: v0.7-integration-tests

---

## US-17: Dockerfile, docker-compose, and README
- **Assignee**: Junior
- **Sprint**: v0.7
- **Story**: As a developer or evaluator, I want a one-command local setup so that the application can be run end-to-end without manual configuration.
- **Acceptance Criteria**:
  - `Dockerfile` uses a multi-stage build: Maven build stage + slim JRE 17 runtime stage
  - `docker-compose.yml` defines `app` and `postgres` services with health-check and env-var wiring
  - `README.md` documents: prerequisites, `docker compose up`, available endpoints, environment variables
  - `docker compose up` starts the app cleanly against PostgreSQL and responds to `GET /actuator/health` with `{"status":"UP"}`
- **Git Checkpoint**: v0.7

---

## Section 3: Sprint Summary Table

| Sprint | Git Tag | Stories | Assignees | Goal |
|--------|---------|---------|-----------|------|
| 1 | v0.1 | US-1, US-2, US-3 | Senior + Junior | Domain model, DTOs, project configuration |
| 2 | v0.2 | US-4, US-5 | Senior | Data layer: repository queries and bounded cache |
| 3 | v0.3 | US-6, US-7 | Senior | Enrichment core: mapper, scorer, pipeline |
| 4 | v0.4 | US-8 | Senior | Ingest endpoint with all validation and hardening |
| 5 | v0.5 | US-9, US-10, US-11, US-12 | Senior + Junior | Query layer: stats, samples, alerts, error handler |
| 6 | v0.6 | US-13, US-14 | Junior + Senior | Dev tooling and security hardening |
| 7 | v0.7 | US-15, US-16, US-17 | Senior + Junior | Tests, Docker, README — release ready |
