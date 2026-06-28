# Mini WAF Security Analytics (Mini WSA)

A take-home assignment demonstrating a production-grade WAF event ingestion and analytics pipeline built with **Java 17 + Spring Boot 3.2**.

## Architecture Overview

```
POST /v1/events/ingest  →  EventController  →  EventIngestionService  →  EnrichmentPipeline  →  EventRepository (JPA)
GET  /v1/stats/summary  →  StatsController  →  StatsService           →  EventRepository
GET  /v1/events/samples →  EventController  →  SamplesService         →  EventRepository
POST /v1/alerts/define  →  AlertController  →  AlertService           →  AlertRepository
GET  /v1/alerts/evaluate→  AlertController  →  AlertService           →  EventRepository
```

See [`doc/HLD.md`](doc/HLD.md) for the full architecture and [`doc/TECH_DESIGN.md`](doc/TECH_DESIGN.md) for the implementation detail.

## Quick Start

### Dev (H2 in-memory)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

App starts on `http://localhost:8080`. H2 console disabled by default.

### Production (PostgreSQL)

```bash
docker-compose up --build
```

Or with an existing Postgres instance:

```bash
export DB_HOST=localhost DB_PORT=5432 DB_NAME=miniwsa DB_USER=miniwsa DB_PASSWORD=secret
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## API Reference

### Ingest Events

```http
POST /v1/events/ingest
Content-Type: application/json

# Single event
{ "eventId": "uuid", "timestamp": "2024-01-15T10:00:00Z", "configId": 1001,
  "clientIp": "203.0.113.5", "path": "/api/data", "method": "POST",
  "statusCode": 403, "rule": { "ruleId": "R001", "severity": "HIGH",
  "category": "INJECTION" }, "action": "DENY" }

# Batch (array of up to 500)
[{ ... }, { ... }]
```

Response `202 Accepted` (async — event queued, not yet persisted):
```json
{ "queued": 1, "eventIds": ["uuid"] }
```

### Stats Summary

```http
GET /v1/stats/summary?configId=1001&from=2024-01-15T00:00:00Z&to=2024-01-16T00:00:00Z
```

### Event Samples

```http
GET /v1/events/samples?configId=1001&category=INJECTION&limit=20&page=0
```

### Define Alert Rule (Bonus)

```http
POST /v1/alerts/define
{ "name": "Injection Spike", "category": "INJECTION", "threshold": 100, "windowMinutes": 5 }
```

### Evaluate Alerts (Bonus)

```http
GET /v1/alerts/evaluate
```

### Seed Synthetic Data (dev only)

```http
POST /dev/generate?count=500&configId=1001
```

## Storage Choice & Big-Data Justification

**This implementation uses PostgreSQL.** Here is the reasoning and the honest trade-off analysis for production scale.

### Why PostgreSQL for this assignment

PostgreSQL gives us strong ACID guarantees, a mature JDBC/JPA ecosystem, and zero operational overhead for a take-home. Every enriched event is written exactly once and deduplicated by primary key (`eventId`), which maps naturally to a relational `UNIQUE` constraint. The aggregation queries (`GROUP BY category`, top-attackers, top-paths) are trivially expressed in SQL and execute in the DB engine rather than in Java heap.

### Honest limits at big-data scale

A real WAF analytics system at Akamai scale ingests **millions of events per second** across thousands of customer configs. PostgreSQL hits a wall in three places:

| Bottleneck | Why it hurts | Production answer |
|---|---|---|
| Single-node write throughput | Even with partitioning, one Postgres primary saturates ~50–100k inserts/sec | Apache Kafka → stream consumers → columnar store |
| Hot time-range scans | `SELECT … WHERE timestamp BETWEEN` on a 10-billion-row table is slow even with indexes | Time-series DB (TimescaleDB, ClickHouse, Apache Druid) |
| Cross-shard aggregation | `topAttackers` across all configIds requires a full table scan | Pre-aggregated materialized views or stream-time rollups |

### What the production architecture would look like

```
WAF edge nodes
    │  (Kafka topics, partitioned by configId)
    ▼
Stream processor (Flink / Kafka Streams)
    │  enrichment, threat scoring, repeat-offender detection (Redis)
    ├──► ClickHouse / Apache Druid   ← stats & aggregation queries
    └──► S3 / object store           ← raw event archive (compliance)

API layer reads from ClickHouse for /stats and /samples
Alert evaluation runs as a continuous streaming query, not on-demand
```

### Why the code is still production-ready as-is

The DAL is fully abstracted behind `EventRepository` (JPA interface). Swapping PostgreSQL for ClickHouse, TimescaleDB, or Cassandra requires only:
1. A new `application-<profile>.yml` with the target datasource
2. A new Spring profile — zero service or controller changes

This is the **IoC / DAL design** principle demonstrated by the implementation.

## IoC / DAL Design

Database is swappable via Spring profile — no code changes needed:

| Profile | Database | Use Case |
|---------|----------|----------|
| `h2` (default) | H2 in-memory | Local dev, unit tests |
| `postgres` | PostgreSQL | Staging, production |

The `RepeatOffenderCache` is also injected via IoC (`CacheConfig`), selected by a single config property:

| `wsa.cache.redis.enabled` | Implementation | Use case |
|---|---|---|
| `false` (default) | `InMemoryRepeatOffenderCache` | Dev, single-instance staging |
| `true` | `RedisRepeatOffenderCache` (sorted sets) | Production, multi-instance |

## Async Ingestion

Ingest returns `202 Accepted` immediately. The actual DB write happens on a `ThreadPoolTaskExecutor` (core 4, max 16, queue 1000). When the queue is full, `CallerRunsPolicy` applies backpressure instead of dropping events.

Timestamp validation (future-timestamp check) runs synchronously before the async dispatch — callers get `422` immediately, not a silent async failure.

## Schema Management

PostgreSQL schema is owned by Flyway (`src/main/resources/db/migration/V1__initial_schema.sql`). Hibernate is set to `ddl-auto: validate` — it verifies the schema matches entities but never modifies it. H2 (dev/test) uses `create-drop` with Flyway disabled.

## Running Tests

```bash
mvn test                          # all tests (uses h2 profile)
mvn test -pl . -Dtest=ThreatScore*  # specific test
```

## Git Checkpoints

| Tag | Content |
|-----|---------|
| `v0.1-ingestion` | Entities, DTOs, ingestion service |
| `v0.2-enrichment` | Enrichment pipeline, threat scoring |
| `v0.3-stats` | Stats + samples endpoints |
| `v0.4-alerts` | Alerting bonus (define + evaluate) |
| `v0.5-web` | Controllers, exception handler, rate limiter |
| `v0.6-docker` | Dockerfile, docker-compose, README |
| `v0.7-tests` | Full test suite |
