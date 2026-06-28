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
│   ├── InMemoryRepeatOffenderCache.java            (@Component)
│   └── RedisRepeatOffenderCache.java               (Redis sorted-set impl, owned by CacheConfig)
│
├── web
│   ├── EventController.java                        (@RestController — 202 Accepted)
│   ├── StatsController.java                        (@RestController)
│   ├── AlertController.java                        (@RestController)
│   ├── DataGenController.java                      (@RestController, @Profile("dev"))
│   ├── GlobalExceptionHandler.java                 (@RestControllerAdvice)
│   └── filter
│       └── RequestIdFilter.java                    (@Order(MIN_VALUE) — MDC requestId)
│
├── config
│   ├── AsyncIngestionConfig.java                   (@EnableAsync — ingestionExecutor bean)
│   ├── CacheConfig.java                            (@ConditionalOnProperty wiring)
│   ├── OpenApiConfig.java
│   └── RateLimitConfig.java
│
└── repository
    └── projection
        ├── CategoryAggregation.java
        ├── ActionAggregation.java
        ├── AttackerAggregation.java
        ├── PathAggregation.java
        └── CategoryCount.java                      (alert batch evaluation projection)
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
  @NotBlank @Size(max=45)
  @Pattern(regexp="^[\\d.:a-fA-F]+$") String clientIp;  // IPv4 + IPv6 only; prevents log injection
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

record SamplesPageResponse(long total, int limit, int page, List<EventSampleResponse> events) {}
```

### Alert DTOs

```java
// RuleInfoDto (nested in EventIngestRequest)
class RuleInfoDto {
  @NotBlank String ruleId;
  @NotBlank String ruleName;  // @NotBlank required — blank names are rejected with 400
  String ruleMessage;         // sanitized (CR/LF stripped) to prevent log injection
  @NotNull Severity severity;
  @NotNull AttackCategory category;
}

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
public interface EventRepository extends JpaRepository<EnrichedEvent, String>,
        JpaSpecificationExecutor<EnrichedEvent> {

  // Stats: total count in window
  @Query("select count(e) from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to")
  long countInWindow(@Param("configId") Long configId,
                     @Param("from") Instant from, @Param("to") Instant to);

  // Stats: by category
  @Query("select e.rule.category as category, count(e) as count, " +
         "avg(e.threatScore) as avgThreatScore from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to group by e.rule.category")
  List<CategoryAggregation> aggregateByCategory(@Param("configId") Long configId,
                                                 @Param("from") Instant from, @Param("to") Instant to);

  // Stats: by action
  @Query("select e.action as action, count(e) as count from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to group by e.action")
  List<ActionAggregation> aggregateByAction(@Param("configId") Long configId,
                                             @Param("from") Instant from, @Param("to") Instant to);

  // Stats: top attackers
  @Query("select e.clientIp as clientIp, count(e) as count, " +
         "avg(e.threatScore) as avgThreatScore from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to " +
         "group by e.clientIp order by count(e) desc")
  List<AttackerAggregation> topAttackers(@Param("configId") Long configId,
                                          @Param("from") Instant from, @Param("to") Instant to,
                                          Pageable pageable);

  // Stats: top paths
  @Query("select e.path as path, count(e) as count from EnrichedEvent e " +
         "where (:configId is null or e.configId = :configId) " +
         "and e.timestamp between :from and :to " +
         "group by e.path order by count(e) desc")
  List<PathAggregation> topPaths(@Param("configId") Long configId,
                                  @Param("from") Instant from, @Param("to") Instant to,
                                  Pageable pageable);

  // Alert evaluation: batch — one GROUP BY query per distinct windowMinutes.
  // Replaces the old N+1 pattern (one countByRuleCategoryAndTimestampAfter per rule).
  @Query("select e.rule.category as category, count(e) as count " +
         "from EnrichedEvent e " +
         "where e.rule.category in :categories and e.timestamp >= :from " +
         "group by e.rule.category")
  List<CategoryCount> countByCategoriesFrom(@Param("categories") List<AttackCategory> categories,
                                             @Param("from") Instant from);

  // Samples: dynamic WHERE built via JpaSpecificationExecutor — no @Query needed.
  // SamplesService passes a Specification<EnrichedEvent> built from filter params.
}
```

Projection interfaces (in `repository.projection`):
```java
interface CategoryAggregation { AttackCategory getCategory(); long getCount(); double getAvgThreatScore(); }
interface ActionAggregation   { ActionType getAction(); long getCount(); }
interface AttackerAggregation { String getClientIp(); long getCount(); double getAvgThreatScore(); }
interface PathAggregation     { String getPath(); long getCount(); }
interface CategoryCount       { AttackCategory getCategory(); long getCount(); }  // alert batch eval
```

### `AlertRepository`

```java
public interface AlertRepository extends JpaRepository<AlertRule, String> { }
```

---

## 5. Service Layer

### `EventIngestionService`

**Flow:** Controller → `validateTimestamps()` (sync, 422 on fail) → `ingestAsync()` fire-and-forget → `202 Accepted`. Worker thread: `toEntity()` → `enrichWithoutRecording()` → `saveAll()` → `afterCommit` hook → `recordInCache()` + counter increment.

```java
@Service @Slf4j
public class EventIngestionService {

  /** Synchronous timestamp guard — called by controller before async dispatch. */
  public void validateTimestamps(List<EventIngestRequest> requests);

  /** Async entry point. @Async + @Transactional — HTTP thread returns 202 immediately. */
  @Async("ingestionExecutor")
  @Transactional
  public void ingestAsync(List<EventIngestRequest> requests);

  /** Synchronous path used by integration tests. */
  @Transactional
  public List<String> ingest(List<EventIngestRequest> requests);

  // Shared core: enrich → saveAll → register afterCommit hook (cache + metrics)
  private List<String> doIngest(List<EventIngestRequest> requests);
  private EnrichedEvent toEntity(EventIngestRequest req);
}
```

Key design decisions:
- `@Transactional` on individual methods (not class) to prevent accidental propagation into helpers.
- `afterCommit` hook: `TransactionSynchronizationManager.registerSynchronization()` runs `recordInCache()` only after durable commit — prevents cache/DB divergence on rollback (C1 fix).
- `DataIntegrityViolationException` caught in `ingestAsync()` — duplicate `eventId` is silently discarded; the UNIQUE DB constraint is the idempotency guard.

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
@Service
public class AlertService {
  /** synchronized — prevents TOCTOU race on max-rules count-check. */
  @Transactional
  public synchronized AlertRuleResponse define(AlertRuleRequest req);

  /**
   * Batched evaluation: groups rules by windowMinutes → one countByCategoriesFrom
   * query per group → at most K queries where K = distinct window sizes.
   * Uses composite key "windowMinutes:CATEGORY" to support same category in different windows.
   */
  @Transactional(readOnly = true)
  public List<AlertEvaluationResult> evaluateAll();
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

Two methods replace the old single `enrich()` to implement the afterCommit pattern (C1 fix):

```java
/** Phase 1 — called inside the transaction. Computes all fields; NO cache writes. */
public EnrichedEvent enrichWithoutRecording(EnrichedEvent e) {
  boolean repeat = cache.isRepeatOffender(e.getClientIp(), e.getTimestamp());
  e.setRepeatOffender(repeat);
  e.setAttackType(mapper.map(e.getRule().getCategory()));
  e.setThreatScore(calculator.calculate(
      e.getRule().getSeverity(), e.getAction(), e.getPath(), repeat));
  return e;
}

/** Phase 2 — called in the afterCommit hook, outside the transaction. */
public void recordInCache(EnrichedEvent e) {
  cache.record(e.getClientIp(), e.getTimestamp());
}
```

This two-phase split ensures: if the DB write rolls back, `recordInCache()` is never called and the repeat-offender cache stays consistent with the database.

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

### `RedisRepeatOffenderCache`

Production-grade distributed implementation using Redis sorted sets:

```
Key:    "wsa:ro:{clientIp}"
Member: "{epochMs}:{UUID}"  — UUID suffix handles sub-millisecond collisions
Score:  epoch_ms

isRepeatOffender: redis.opsForZSet().count(key, windowStartMs, nowMs) >= threshold  — O(log N)
record:  ZADD key score member
         ZREMRANGEBYSCORE key 0 (windowStartMs - 1)   — prune expired entries
         EXPIRE key (windowMinutes + 1) * 60          — TTL bound memory
```

Selected via `@ConditionalOnProperty(name = "wsa.cache.redis.enabled", havingValue = "true")` in `CacheConfig`. Falls back to `InMemoryRepeatOffenderCache` via `@ConditionalOnMissingBean` when Redis is not enabled.

---

## 8. Controller Layer

### `EventController` (`/v1/events`)

```java
@PostMapping("/ingest")
ResponseEntity<IngestResponse> ingest(@RequestBody JsonNode body);
// Accepts single object OR array — detect via node.isArray()
// Calls validateTimestamps() synchronously → 422 on future timestamps
// Calls ingestAsync() fire-and-forget → 202 Accepted { "queued": N, "eventIds": [...] }

@GetMapping("/samples")
SamplesPageResponse samples(
  @RequestParam(required=false) Long configId,
  @RequestParam(required=false) Instant from,
  @RequestParam(required=false) Instant to,
  @RequestParam(required=false) AttackCategory category,
  @RequestParam(required=false) ActionType action,
  @RequestParam(defaultValue="20") @Max(100) int limit,
  @RequestParam(defaultValue="0") int page          // page-number (0-indexed), replaces offset
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
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:miniwsa}
    username: ${DB_USER:miniwsa}
    password: ${DB_PASSWORD:miniwsa_secret}
    hikari:
      minimum-idle: 5
      connection-timeout: 5000
      keepalive-time: 60000
  flyway:
    enabled: true
    locations: classpath:db/migration   # V1__initial_schema.sql owns all DDL
  jpa:
    hibernate.ddl-auto: validate        # Hibernate validates only; Flyway manages schema
    properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
```

### `application-h2.yml`

```yaml
spring:
  flyway:
    enabled: false   # Flyway disabled for H2 — JPA create-drop handles test schema
  jpa:
    hibernate.ddl-auto: create-drop
```

### `CacheConfig`

```java
@Configuration @EnableScheduling
public class CacheConfig {

  /** Active when wsa.cache.redis.enabled=true. Requires spring.data.redis.* config. */
  @Bean
  @ConditionalOnProperty(name = "wsa.cache.redis.enabled", havingValue = "true")
  public RepeatOffenderCache redisRepeatOffenderCache(
      StringRedisTemplate redis,
      @Value("${wsa.cache.window-minutes:10}") int windowMinutes,
      @Value("${wsa.cache.repeat-offender-threshold:5}") int threshold) {
    return new RedisRepeatOffenderCache(redis, Duration.ofMinutes(windowMinutes), threshold);
  }

  /** Fallback when Redis is not enabled. Warns in Javadoc: degrades on 2+ instances. */
  @Bean
  @ConditionalOnMissingBean(RepeatOffenderCache.class)
  public RepeatOffenderCache inMemoryRepeatOffenderCache(
      @Value("${wsa.cache.window-minutes:10}") int windowMinutes,
      @Value("${wsa.cache.repeat-offender-threshold:5}") int threshold,
      @Value("${wsa.cache.max-ip-entries:10000}") int maxEntries) {
    return new InMemoryRepeatOffenderCache(windowMinutes, threshold, maxEntries);
  }
}
```

### `AsyncIngestionConfig`

```java
@Configuration @EnableAsync
public class AsyncIngestionConfig {

  @Bean("ingestionExecutor")
  public Executor ingestionExecutor(
      @Value("${wsa.ingestion.async.core-pool-size:4}") int coreSize,
      @Value("${wsa.ingestion.async.max-pool-size:16}") int maxSize,
      @Value("${wsa.ingestion.async.queue-capacity:1000}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(coreSize);
    executor.setMaxPoolSize(maxSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("wsa-ingest-");
    // CallerRunsPolicy: when queue is full, the calling thread (Tomcat) runs the task.
    // This provides backpressure — slows callers rather than dropping events.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
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
| `EventIngestionIntegrationTest` | `@SpringBootTest` MockMvc | POST single → **202**; POST array → 202 with `queued` count; invalid `statusCode` → 400; missing required field → 400; future timestamp → 422; invalid IP → 400; duplicate eventId → both 202 (async idempotency) |
| `StatsSummaryIntegrationTest` | `@SpringBootTest` MockMvc | Seed events; GET summary → totalEvents; byCategory counts; topAttackers sorted desc; topPaths; `?page=0` pagination |
| `AlertIntegrationTest` | `@SpringBootTest` MockMvc | define → 201; evaluate with seeded events ≥ threshold → firing; evaluate below threshold → not firing |
| `SamplesIntegrationTest` | `@SpringBootTest` MockMvc | Pagination (`?page=0`, `?page=1`); filter by category; filter by action; total correct with filter; timestamp desc ordering |

Test infra:
- `@ActiveProfiles("h2")` on all integration tests (Flyway disabled, H2 create-drop).
- `@TestConfiguration` inner class overrides `ingestionExecutor` with `SyncTaskExecutor` in both `EventIngestionIntegrationTest` and `StatsSummaryIntegrationTest` — makes `@Async` deterministic in tests (events in DB before assertions).
- `@Transactional` + `@Rollback` per test method for isolation.
- Deterministic `Clock` bean (`Clock.fixed(...)`) for repeat-offender window tests.

---

## 12. Docker Compose

### `docker-compose.yml`

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: miniwsa
      POSTGRES_USER: miniwsa
      POSTGRES_PASSWORD: miniwsa_secret
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U miniwsa"]
      interval: 10s
      retries: 5
    volumes: ["postgres_data:/var/lib/postgresql/data"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      retries: 5

  app:
    build: .
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: postgres
      DB_HOST: postgres
      DB_NAME: miniwsa
      DB_USER: miniwsa
      DB_PASSWORD: miniwsa_secret
      SPRING_DATA_REDIS_HOST: redis
      WSA_CACHE_REDIS_ENABLED: "true"
    ports: ["8080:8080"]

volumes:
  postgres_data:
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
