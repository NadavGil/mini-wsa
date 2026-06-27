# TECH_DESIGN.md — Mini WSA (Mini Web Security Analytics)

> Technical Design Document. Implements the Architect's HLD. Target: Spring Boot 3.2, Java 17, Spring Data JPA. Storage: H2 (default/test), PostgreSQL (prod) selected via `spring.profiles.active`. Implementation maps to git tags `v0.1-ingestion` … `v0.7-tests`.

---

## 1. Package Structure

Base package: `com.akamai.miniwsa`

```
com.akamai.miniwsa
├── MiniWsaApplication.java                         (Spring Boot main)
│
├── domain
│   ├── EnrichedEvent.java                          (@Entity)
│   ├── RuleInfo.java                               (@Embeddable)
│   ├── GeoLocation.java                            (@Embeddable)
│   ├── AlertRule.java                              (@Entity)
│   ├── AttackCategory.java                         (enum)
│   ├── ActionType.java                             (enum)
│   └── Severity.java                               (enum)
│
├── dto
│   ├── ingest
│   │   ├── EventIngestRequest.java
│   │   ├── RuleInfoDto.java
│   │   └── GeoLocationDto.java
│   ├── stats
│   │   ├── StatsSummaryResponse.java
│   │   ├── CategoryStat.java
│   │   ├── ActionStat.java
│   │   ├── AttackerStat.java
│   │   └── PathStat.java
│   ├── samples
│   │   ├── EventSampleResponse.java
│   │   └── SamplesPageResponse.java
│   ├── alerts
│   │   ├── AlertRuleRequest.java
│   │   ├── AlertRuleResponse.java
│   │   └── AlertEvaluationResult.java
│   └── error
│       └── ApiError.java
│
├── repository
│   ├── EventRepository.java
│   ├── AlertRepository.java
│   └── projection
│       ├── CategoryAggregation.java
│       ├── ActionAggregation.java
│       ├── AttackerAggregation.java
│       └── PathAggregation.java
│
├── service
│   ├── EventIngestionService.java
│   ├── StatsService.java
│   ├── SamplesService.java
│   ├── AlertService.java
│   └── DataGeneratorService.java
│
├── enrichment
│   ├── AttackTypeMapper.java                       (@Component)
│   ├── ThreatScoreCalculator.java                  (@Component)
│   ├── EnrichmentPipeline.java                     (@Component)
│   ├── RepeatOffenderCache.java                    (interface)
│   └── InMemoryRepeatOffenderCache.java            (@Component)
│
├── web
│   ├── EventController.java                        (@RestController)
│   ├── StatsController.java                        (@RestController)
│   ├── AlertController.java                        (@RestController)
│   ├── DataGenController.java                      (@RestController, @Profile("dev"))
│   └── GlobalExceptionHandler.java                 (@RestControllerAdvice)
│
└── config
    ├── CacheConfig.java
    └── OpenApiConfig.java
```

---

## 2. Entity Classes

### `EnrichedEvent` (@Entity, table `enriched_event`)

| Field | Type | JPA Annotation | Notes |
|---|---|---|---|
| eventId | String | `@Id` | Primary key from payload |
| timestamp | Instant | `@Column(nullable=false)` | Indexed |
| configId | Long | `@Column(nullable=false)` | Indexed |
| policyId | String | `@Column` | |
| clientIp | String | `@Column(nullable=false)` | Indexed |
| hostname | String | `@Column` | |
| path | String | `@Column(length=2048)` | |
| method | String | `@Column(length=10)` | |
| statusCode | Integer | `@Column` | |
| userAgent | String | `@Column(length=1024)` | |
| rule | RuleInfo | `@Embedded` | |
| action | ActionType | `@Enumerated(EnumType.STRING)` | |
| geoLocation | GeoLocation | `@Embedded` | |
| requestSize | Long | `@Column` | |
| responseSize | Long | `@Column` | |
| **threatScore** | Integer | `@Column(nullable=false)` | Enriched |
| **attackType** | String | `@Column` | Enriched |
| **repeatOffender** | boolean | `@Column` | Enriched |
| ingestedAt | Instant | `@Column` | Server receive time |

Table-level indexes:
```java
@Table(indexes = {
  @Index(name="idx_ts",           columnList="timestamp"),
  @Index(name="idx_config_ts",    columnList="configId,timestamp"),
  @Index(name="idx_ip_ts",        columnList="clientIp,timestamp"),
  @Index(name="idx_category",     columnList="rule_category")
})
```

### `RuleInfo` (@Embeddable)

| Field | Type | Column Name |
|---|---|---|
| id | String | `rule_id` |
| name | String | `rule_name` |
| message | String | `rule_message` |
| severity | Severity (enum) | `rule_severity` |
| category | AttackCategory (enum) | `rule_category` |

### `GeoLocation` (@Embeddable)

| Field | Type | Column Name |
|---|---|---|
| country | String | `geo_country` |
| city | String | `geo_city` |

### `AlertRule` (@Entity, table `alert_rule`)

| Field | Type | Notes |
|---|---|---|
| id | String | UUID, `@Id` |
| name | String | `@Column(nullable=false)` |
| category | AttackCategory | `@Enumerated(EnumType.STRING)` |
| threshold | Integer | `@Column(nullable=false)` |
| windowMinutes | Integer | `@Column(nullable=false)` |
| createdAt | Instant | `@Column` |

### Enums

```java
enum Severity    { CRITICAL, HIGH, MEDIUM, LOW }
enum ActionType  { DENY, ALERT, MONITOR }
enum AttackCategory { INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT }
```

---

## 3. DTO Classes

### `EventIngestRequest` (Bean Validation)

```java
public class EventIngestRequest {
  @NotBlank                    String eventId;
  @NotNull                     Instant timestamp;
  @NotNull                     Long configId;
                               String policyId;
  @NotBlank                    String clientIp;
                               String hostname;
                               String path;
                               String method;
  @Min(100) @Max(599)          Integer statusCode;
                               String userAgent;
  @NotNull @Valid              RuleInfoDto rule;
  @NotNull                     ActionType action;
  @Valid                       GeoLocationDto geoLocation;
  @Min(0)                      Long requestSize;
  @Min(0)                      Long responseSize;
}
```

### `StatsSummaryResponse`

```java
record StatsSummaryResponse(
  long totalEvents,
  List<CategoryStat> byCategory,
  List<ActionStat> byAction,
  List<AttackerStat> topAttackers,
  List<PathStat> topTargetedPaths
) {}

record CategoryStat(AttackCategory category, long count, double avgThreatScore) {}
record ActionStat(ActionType action, long count) {}
record AttackerStat(String clientIp, long count, double avgThreatScore) {}
record PathStat(String path, long count) {}
```

### `EventSampleResponse` / `SamplesPageResponse`

```java
record EventSampleResponse(
  String eventId, Instant timestamp, Long configId, String clientIp,
  String path, String method, Integer statusCode,
  AttackCategory category, ActionType action,
  String attackType, int threatScore, boolean repeatOffender, String country
) {}

record SamplesPageResponse(long total, int limit, int offset, List<EventSampleResponse> events) {}
```

### Alert DTOs

```java
// Request
class AlertRuleRequest {
  @NotBlank String name;
  @NotNull  AttackCategory category;
  @NotNull @Min(1) Integer threshold;
  @NotNull @Min(1) Integer windowMinutes;
}

// Responses
record AlertRuleResponse(String id, String name, AttackCategory category,
                         int threshold, int windowMinutes, Instant createdAt) {}

record AlertEvaluationResult(
  String ruleId, String name, AttackCategory category,
  int threshold, int windowMinutes,
  long observedCount, boolean firing, Instant evaluatedAt
) {}
```

### `ApiError`

```java
record ApiError(Instant timestamp, int status, String error,
                String message, List<FieldViolation> violations) {
  record FieldViolation(String field, String reason) {}
}
```

---

## 4. Repository Interfaces

### `EventRepository`

```java
public interface EventRepository extends JpaRepository<EnrichedEvent, String> {

  // Repeat offender check
  long countByClientIpAndTimestampAfter(String clientIp, Instant cutoff);

  // Stats: total count in window
  @Query("select count(e) from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to")
  long countInWindow(Long configId, Instant from, Instant to);

  // Stats: by category
  @Query("select e.rule.category as category, count(e) as count, " +
         "avg(e.threatScore) as avgThreatScore from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to group by e.rule.category")
  List<CategoryAggregation> aggregateByCategory(Long configId, Instant from, Instant to);

  // Stats: by action
  @Query("select e.action as action, count(e) as count from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to group by e.action")
  List<ActionAggregation> aggregateByAction(Long configId, Instant from, Instant to);

  // Stats: top 10 attackers
  @Query("select e.clientIp as clientIp, count(e) as count, " +
         "avg(e.threatScore) as avgThreatScore from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to " +
         "group by e.clientIp order by count(e) desc")
  List<AttackerAggregation> topAttackers(Long configId, Instant from, Instant to, Pageable p);

  // Stats: top 10 paths
  @Query("select e.path as path, count(e) as count from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to " +
         "group by e.path order by count(e) desc")
  List<PathAggregation> topPaths(Long configId, Instant from, Instant to, Pageable p);

  // Samples: dynamic filters
  @Query("select e from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and (:from is null or e.timestamp >= :from) " +
         "and (:to is null or e.timestamp <= :to) " +
         "and (:category is null or e.rule.category = :category) " +
         "and (:action is null or e.action = :action) " +
         "order by e.timestamp desc")
  List<EnrichedEvent> findSamples(Long configId, Instant from, Instant to,
                                  AttackCategory category, ActionType action, Pageable p);

  @Query("select count(e) from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and (:from is null or e.timestamp >= :from) " +
         "and (:to is null or e.timestamp <= :to) " +
         "and (:category is null or e.rule.category = :category) " +
         "and (:action is null or e.action = :action)")
  long countSamples(Long configId, Instant from, Instant to,
                    AttackCategory category, ActionType action);

  // Alert evaluation
  long countByRuleCategoryAndTimestampAfter(AttackCategory category, Instant cutoff);
}
```

Projection interfaces (in `repository.projection`):
```java
interface CategoryAggregation { AttackCategory getCategory(); long getCount(); double getAvgThreatScore(); }
interface ActionAggregation   { ActionType getAction(); long getCount(); }
interface AttackerAggregation { String getClientIp(); long getCount(); double getAvgThreatScore(); }
interface PathAggregation     { String getPath(); long getCount(); }
```

### `AlertRepository`

```java
public interface AlertRepository extends JpaRepository<AlertRule, String> { }
```

---

## 5. Service Layer

### `EventIngestionService`

**Flow:** `EventIngestRequest` → copy fields → `enrich()` → `saveAll()` → return IDs.

```java
@Service @Transactional
public class EventIngestionService {
  public List<String> ingest(List<EventIngestRequest> requests);
  private EnrichedEvent toEntity(EventIngestRequest req);
}
```

Ingest is **atomic per batch**: one invalid row → 400 for whole call (validation done at controller layer before service is invoked).

### `StatsService`

```java
@Service
public class StatsService {
  public StatsSummaryResponse summary(Long configId, Instant from, Instant to);
  // Defaults: to=now, from=now-24h if null
}
```

### `SamplesService`

```java
@Service
public class SamplesService {
  public SamplesPageResponse samples(Long configId, Instant from, Instant to,
    AttackCategory category, ActionType action, int limit, int offset);
  // limit default=20, max=100
}
```

### `AlertService`

```java
@Service @Transactional
public class AlertService {
  public AlertRuleResponse define(AlertRuleRequest req);
  public List<AlertEvaluationResult> evaluateAll();
  // evaluate: for each rule, count events in window, firing = count >= threshold
}
```

---

## 6. Enrichment Pipeline

### `AttackTypeMapper`

```java
private static final Map<AttackCategory, String> MAP = Map.of(
  INJECTION,          "SQL/Command Injection",
  XSS,                "Cross-Site Scripting",
  PROTOCOL_VIOLATION, "Protocol Anomaly",
  DATA_LEAKAGE,       "Data Exfiltration",
  BOT,                "Bot Activity",
  DOS,                "Denial of Service",
  RATE_LIMIT,         "Rate Limiting"
);
public String map(AttackCategory c) { return MAP.getOrDefault(c, "Unknown"); }
```

### `ThreatScoreCalculator`

```
score  = switch(severity) { CRITICAL→40, HIGH→30, MEDIUM→20, LOW→10, null→0 }
score += switch(action)   { DENY→+20, ALERT→+10, MONITOR→+0, null→0 }
if (path != null && (path.toLowerCase().contains("/admin") ||
                     path.toLowerCase().contains("/login"))) score += 15
if (repeatOffender) score += 15
return Math.min(score, 100)
```

Max raw = 90 (CRITICAL+DENY+path+repeat), cap is a safety net for future weight changes.

### `EnrichmentPipeline`

```java
public EnrichedEvent enrich(EnrichedEvent e) {
  // 1. Check repeat-offender BEFORE recording this event
  boolean repeat = cache.isRepeatOffender(e.getClientIp(), e.getTimestamp());
  // 2. Record this event in cache
  cache.record(e.getClientIp(), e.getTimestamp());
  // 3. Set enriched fields
  e.setRepeatOffender(repeat);
  e.setAttackType(mapper.map(e.getRule().getCategory()));
  e.setThreatScore(calculator.calculate(
      e.getRule().getSeverity(), e.getAction(), e.getPath(), repeat));
  return e;
}
```

---

## 7. RepeatOffenderCache

### Interface

```java
public interface RepeatOffenderCache {
  boolean isRepeatOffender(String clientIp, Instant eventTime); // true if >5 events in last 10 min
  void record(String clientIp, Instant eventTime);
}
```

### `InMemoryRepeatOffenderCache`

```java
private static final Duration WINDOW = Duration.ofMinutes(10);
private static final int THRESHOLD = 5;
private final ConcurrentHashMap<String, Deque<Instant>> ipTimestamps = new ConcurrentHashMap<>();

public boolean isRepeatOffender(String ip, Instant eventTime) {
  Deque<Instant> deque = ipTimestamps.getOrDefault(ip, new ConcurrentLinkedDeque<>());
  pruneOlderThan(deque, eventTime.minus(WINDOW));
  return deque.size() > THRESHOLD;
}

public void record(String ip, Instant eventTime) {
  ipTimestamps.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>()).addLast(eventTime);
}

@Scheduled(fixedRate = 60_000)
public void scheduledPrune() { /* remove empty deques, prune stale entries */ }
```

A `JpaRepeatOffenderCache` alternative delegates to `EventRepository.countByClientIpAndTimestampAfter` for stateless/clustered deployments.

---

## 8. Controller Layer

### `EventController` (`/v1/events`)

```java
@PostMapping("/ingest")
ResponseEntity<IngestResponse> ingest(@RequestBody JsonNode body);
// Accepts single object OR array — detect via node.isArray()
// Validate each element, collect violations, return 400 if any
// Returns: 201 { "ingested": N, "eventIds": [...] }

@GetMapping("/samples")
SamplesPageResponse samples(
  @RequestParam(required=false) Long configId,
  @RequestParam(required=false) Instant from,
  @RequestParam(required=false) Instant to,
  @RequestParam(required=false) AttackCategory category,
  @RequestParam(required=false) ActionType action,
  @RequestParam(defaultValue="20") @Max(100) int limit,
  @RequestParam(defaultValue="0") int offset
);
```

### `StatsController` (`/v1/stats`)

```java
@GetMapping("/summary")
StatsSummaryResponse summary(
  @RequestParam(required=false) Long configId,
  @RequestParam(required=false) Instant from,
  @RequestParam(required=false) Instant to
);
```

### `AlertController` (`/v1/alerts`)

```java
@PostMapping("/define")
ResponseEntity<AlertRuleResponse> define(@Valid @RequestBody AlertRuleRequest req); // 201

@GetMapping("/evaluate")
List<AlertEvaluationResult> evaluate();
```

### `GlobalExceptionHandler`

| Exception | HTTP Status | Response |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `ApiError` with field violations |
| `ConstraintViolationException` | 400 | `ApiError` with field violations |
| `HttpMessageNotReadableException` | 400 | `ApiError` — malformed JSON |
| `IllegalArgumentException` | 400 | `ApiError` — bad param value |
| `NoSuchElementException` | 404 | `ApiError` |
| `Exception` (fallback) | 500 | Generic message, no stack trace |

---

## 9. Configuration

### `application.yml`

```yaml
spring:
  application.name: mini-wsa
  profiles.active: h2
  jackson:
    serialization.write-dates-as-timestamps: false
    deserialization.fail-on-unknown-properties: false
    time-zone: UTC
server:
  port: 8080
springdoc:
  swagger-ui.path: /swagger
```

### `application-h2.yml`

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:wsa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  h2.console.enabled: true
  jpa:
    hibernate.ddl-auto: create-drop
    show-sql: false
```

### `application-postgres.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:wsa}
    username: ${DB_USER:wsa}
    password: ${DB_PASSWORD:wsa}
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
```

### `CacheConfig`

```java
@Configuration
public class CacheConfig {
  @Bean @Profile("!postgres")
  public RepeatOffenderCache inMemoryCache() {
    return new InMemoryRepeatOffenderCache();
  }
  @Bean @Profile("postgres")
  public RepeatOffenderCache jpaCache(EventRepository repo) {
    return new JpaRepeatOffenderCache(repo);
  }
}
```

---

## 10. Data Generator

### `DataGeneratorService`

```java
@Service
public class DataGeneratorService {
  // Configurable parameters
  public GenerationSummary generate(int count, int waveCount, int waveSize);
}
```

**Algorithm:**
1. Pre-compute `waveCount` attack IPs, each will generate `waveSize` events within a 30-second window.
2. Fill remaining `count - (waveCount * waveSize)` events with random IPs, categories, paths spread over last 24h.
3. Mix events (shuffle timestamp order so waves are interleaved with noise).
4. Route through `EventIngestionService.ingest()` in batches of 500.

Attack wave detail: pick IP from `attackIps`, set timestamp within `[waveStart, waveStart + 30s]`, random `configId` from pool of 3, category biased toward INJECTION/BOT for waves, severity CRITICAL/HIGH, action DENY. This guarantees repeat-offender bonus fires for wave IPs.

### Trigger

```java
@RestController @RequestMapping("/v1/dev") @Profile("dev")
public class DataGenController {
  @PostMapping("/generate")
  GenerationSummary generate(
    @RequestParam(defaultValue="1000") int count,
    @RequestParam(defaultValue="5") int waveCount,
    @RequestParam(defaultValue="20") int waveSize
  );
}
```

Also available as `@Component CommandLineRunner @Profile("seed")` reading `wsa.seed.count` property.

---

## 11. Test Strategy

| Class | Type | What It Tests |
|---|---|---|
| `ThreatScoreCalculatorTest` | Unit | All severity weights; all action bonuses; path heuristic case-insensitivity (`/ADMIN`, `/Login`); repeat offender bonus; cap at 100; null inputs = 0 contribution; max raw (90) stays under cap |
| `AttackTypeMapperTest` | Unit | Every `AttackCategory` → expected label; null/unknown → "Unknown" |
| `InMemoryRepeatOffenderCacheTest` | Unit | 5 prior events → not repeat; 6th → repeat; events outside 10-min window excluded; separate IPs isolated; prune removes stale entries |
| `AlertEvaluationTest` | Unit (Mockito) | firing when observed ≥ threshold; not firing when below; window boundary (event at exactly cutoff) |
| `EventRepositoryTest` | `@DataJpaTest` H2 | `countByClientIpAndTimestampAfter`; `aggregateByCategory` counts + avg; `aggregateByAction`; `topAttackers` top-3 ordering; `topPaths`; `findSamples` with null filters; `findSamples` with category+action filter; `countSamples` |
| `EventIngestionIntegrationTest` | `@SpringBootTest` MockMvc | POST single → 201; POST array → 201 count; invalid `statusCode` → 400 with violations; missing required field → 400; duplicate eventId handling; verify enrichment fields in DB |
| `StatsSummaryIntegrationTest` | `@SpringBootTest` MockMvc | Seed 20 events; GET summary → totalEvents; byCategory counts; topAttackers sorted desc; topPaths; time-range filter excludes events outside range |
| `AlertIntegrationTest` | `@SpringBootTest` MockMvc | define → 201; evaluate with seeded events above threshold → firing; evaluate below threshold → not firing |
| `SamplesIntegrationTest` | `@SpringBootTest` MockMvc | Pagination (limit=5 offset=5); filter by category; filter by action; `total` correct with filter; timestamp desc ordering |

Test infra: `@ActiveProfiles("h2")` on all integration tests; `@Transactional` + `@Rollback` per test method; deterministic `Clock` bean (`Clock.fixed(...)`) injected for repeat-offender window tests.

---

## 12. Docker Compose

### `docker-compose.yml`

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: wsa
      POSTGRES_USER: wsa
      POSTGRES_PASSWORD: wsa
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U wsa"]
      interval: 5s
      retries: 5
    volumes: ["pgdata:/var/lib/postgresql/data"]

  app:
    build: .
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: postgres
      DB_HOST: postgres
      DB_NAME: wsa
      DB_USER: wsa
      DB_PASSWORD: wsa
    ports: ["8080:8080"]

volumes:
  pgdata:
```

### `Dockerfile`

```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

---

## 13. README Outline

1. Overview — what Mini WSA does
2. Architecture diagram (Mermaid)
3. Tech stack
4. Quick start — `./mvnw spring-boot:run` (H2 default), Swagger at `/swagger`
5. Spring profiles — h2, postgres, dev, seed
6. Docker — `docker compose up`
7. API reference — curl examples for all 5 endpoints
8. Threat score formula table
9. Repeat-offender logic explanation
10. Data generator usage
11. Testing — `./mvnw test`
12. Configuration reference (env vars)
13. Git tag roadmap — v0.1 through v0.7
14. Known limitations / future work

---

*End of TECH_DESIGN.md*
