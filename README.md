# payment-processing-engine

A production-grade payment processing backend built with Java 21, Spring Boot 3, and an event-driven microservices architecture. Demonstrates real-world patterns used in high-throughput FinTech systems: idempotency, saga orchestration, circuit breaking, and rate limiting.

> **Context:** Built to showcase backend engineering patterns from 13+ years of experience in payments and distributed systems, including a real-time payment platform processing 2M+ transactions/day at sub-100ms P99 latency.

---

## Architecture Overview

```
Client
  │
  ▼
┌─────────────────────────────────────┐
│         REST API (Spring Boot)      │  ← Rate Limiter (Resilience4j)
│     /api/v1/payments                │
└──────────────┬──────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────┐
│                  PaymentService (Saga Orchestrator)  │
│                                                      │
│  1. Idempotency check  ──► Redis (fast path, O(1))  │
│  2. Persist INITIATED  ──► PostgreSQL               │
│  3. Cache mapping      ──► Redis                    │
│  4. Transition PROCESSING + publish event ──► Kafka │
│  5. Gateway call       ──► Circuit Breaker          │
│  6. Transition COMPLETED + publish event ──► Kafka  │
└──────────────────────────────────────────────────────┘
               │
     ┌─────────┴──────────┐
     ▼                    ▼
  Kafka                PostgreSQL
  Topics:              payments table
  - payments.initiated  (Flyway schema mgmt)
  - payments.completed
     │
     ▼
Downstream consumers (notification, ledger, audit services)
```

---

## Key Engineering Patterns

### 1. Idempotency — Safe Retries at Scale

A core challenge in payments: clients retry on network failure. Without idempotency, a retry creates a duplicate charge.

**Two-layer defence:**
- **Layer 1 (Redis):** O(1) check on `idempotency:{key}` — returns cached result in sub-millisecond
- **Layer 2 (PostgreSQL):** `UNIQUE` constraint on `idempotency_key` — catches the race window where two identical requests arrive before either completes

```java
// Fast path: Redis cache check
var cachedId = idempotencyStore.getCachedPaymentId(request.idempotencyKey());
if (cachedId.isPresent()) {
    return paymentRepository.findById(UUID.fromString(cachedId.get()))
        .map(PaymentResponse::from)
        .orElseThrow();
}
// Safety net: DB unique constraint + optimistic locking
```

### 2. Saga Pattern — Distributed Transaction Management

The payment flow is a saga: a sequence of local transactions with compensating rollbacks on failure.

```
INITIATED → PROCESSING → COMPLETED
                    ↓
                 FAILED → (retry with exponential backoff)
```

State transitions are enforced by the domain model — invalid transitions throw `IllegalStateException`, preventing corrupt states from being persisted.

```java
// Enforced state machine in Payment.java
private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
    INITIATED,  Set.of(PROCESSING),
    PROCESSING, Set.of(COMPLETED, FAILED),
    COMPLETED,  Set.of(),
    FAILED,     Set.of(PROCESSING)   // retry path
);
```

### 3. Circuit Breaker — Prevent Cascade Failures

Wraps the downstream payment gateway call. If the gateway fails 50% of requests over a 10-request window, the circuit opens and we fail fast for 30 seconds — preventing thread pool exhaustion and cascading failures.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentGateway:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 10
```

### 4. Event-Driven Architecture — Decoupled Downstream Services

Payment events are published to Kafka topics keyed by `paymentId` — guaranteeing partition affinity and ordered delivery for all events from the same payment.

| Topic | Published when | Consumers |
|---|---|---|
| `payments.initiated` | Payment moves to PROCESSING | Notification, Audit, Risk service |
| `payments.completed` | Payment successfully settled | Ledger, Notification service |

Kafka producer is configured with `acks=all` + `enable.idempotence=true` for exactly-once semantics.

### 5. Rate Limiting

Configured at 100 requests/second per service instance via Resilience4j `RateLimiter`. In a multi-instance production setup, this moves to API Gateway (AWS API Gateway, Nginx, Kong) for distributed rate limiting.

### 6. Retry with Exponential Backoff

Failed payments are retried by a scheduled job with exponential backoff:
- Attempt 1: after 2 minutes
- Attempt 2: after 4 minutes  
- Attempt 3: after 8 minutes
- Beyond 3 retries: terminal FAILED state

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (Records, Sealed classes, Pattern matching) |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 + Flyway migrations |
| Cache | Redis 7 (Lettuce client, connection pooling) |
| Messaging | Apache Kafka (Spring Kafka) |
| Resilience | Resilience4j (Circuit Breaker, Rate Limiter) |
| ORM | Spring Data JPA + Hibernate |
| Observability | Micrometer + Prometheus + Grafana |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Testing | JUnit 5, Mockito, Testcontainers |
| CI/CD | GitHub Actions |

---

## Running Locally

**Prerequisites:** Docker, Java 21, Maven 3.9+

```bash
# 1. Start infrastructure (PostgreSQL, Redis, Kafka, Kafka UI, Prometheus, Grafana)
docker-compose up -d

# 2. Build and run
./mvnw spring-boot:run

# 3. Access Swagger UI
open http://localhost:8080/swagger-ui.html

# 4. Access Kafka UI (view topics and messages)
open http://localhost:8090

# 5. Access Grafana dashboards
open http://localhost:3000  # admin / admin
```

---

## API Reference

### Initiate Payment
```
POST /api/v1/payments
Content-Type: application/json
```

```json
{
  "idempotencyKey": "client-uuid-001",
  "sourceAccountId": "ACC-1001",
  "destinationAccountId": "ACC-2001",
  "amount": 5000.00,
  "currency": "INR"
}
```

**Response (201 Created):**
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "idempotencyKey": "client-uuid-001",
  "sourceAccountId": "ACC-1001",
  "destinationAccountId": "ACC-2001",
  "amount": 5000.00,
  "currency": "INR",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

**Idempotency:** Sending the same `idempotencyKey` again returns the original response — no duplicate processing.

### Get Payment by ID
```
GET /api/v1/payments/{paymentId}
```

### Get Payment by Idempotency Key
```
GET /api/v1/payments/idempotency/{idempotencyKey}
```

---

## Database Schema

```sql
CREATE TABLE payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key         VARCHAR(64)    NOT NULL UNIQUE,  -- Safe retry guarantee
    source_account_id       VARCHAR(64)    NOT NULL,
    destination_account_id  VARCHAR(64)    NOT NULL,
    amount                  NUMERIC(18,2)  NOT NULL CHECK (amount > 0),
    currency                CHAR(3)        NOT NULL,
    status                  VARCHAR(20)    NOT NULL DEFAULT 'INITIATED',
    failure_reason          VARCHAR(512),
    retry_count             INTEGER        NOT NULL DEFAULT 0,
    last_attempt_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                 BIGINT         NOT NULL DEFAULT 0  -- Optimistic locking
);
```

Key indexes: `idempotency_key` (unique), `status`, `source_account_id`, `created_at DESC`, and a partial index on `(status, retry_count, last_attempt_at) WHERE status = 'FAILED'` for the retry scheduler.

---

## Observability

The service exposes Prometheus metrics at `/actuator/prometheus`. Key metrics:

| Metric | Description |
|---|---|
| `payment.initiated.count` | Total payments initiated |
| `payment.completed.count` | Total successful payments |
| `payment.failed.count` | Total failed payments |
| `resilience4j.circuitbreaker.state` | Circuit breaker state (CLOSED/OPEN/HALF_OPEN) |
| `http.server.requests` | API latency percentiles (P50/P95/P99) |

---

## Design Decisions & Trade-offs

| Decision | Chosen approach | Alternative considered | Reason |
|---|---|---|---|
| Idempotency storage | Redis + DB unique constraint | DB only | Redis gives sub-ms fast path; DB is safety net for race conditions |
| Event publishing | Async Kafka (fire-and-forget with callback) | Transactional outbox | Simpler for demo; outbox pattern for guaranteed delivery in production |
| State machine | Enum with transition map | Spring State Machine | Lightweight, zero dependencies, easy to reason about |
| Retry | Scheduled job | Kafka retry topics | Predictable backoff; retry topics better for very high volume |
| Locking | Optimistic (@Version) | Pessimistic SELECT FOR UPDATE | Higher throughput; pessimistic only when contention is guaranteed high |

---

## Project Structure

```
src/main/java/com/rajasekhar/payment/
├── controller/          # REST API layer
├── service/             # Business logic + saga orchestration
├── domain/
│   ├── model/           # Payment aggregate, PaymentStatus state machine
│   └── event/           # Domain events (PaymentInitiatedEvent, PaymentCompletedEvent)
├── repository/          # Spring Data JPA repositories
├── infrastructure/
│   ├── kafka/           # PaymentEventPublisher
│   └── redis/           # IdempotencyStore
├── dto/
│   ├── request/         # PaymentRequest (validated)
│   └── response/        # PaymentResponse
└── exception/           # Custom exceptions + GlobalExceptionHandler
```

---

## What's Next (Roadmap)

- [ ] Transactional outbox pattern for guaranteed Kafka delivery
- [ ] Distributed rate limiting via Redis (replace per-instance Resilience4j)
- [ ] Payment gateway integration (Razorpay / NPCI UPI)
- [ ] gRPC internal API for ledger service communication
- [ ] Kubernetes deployment manifests (HPA, readiness/liveness probes)
- [ ] Distributed tracing (OpenTelemetry + Jaeger)

---

## Author

**Rajasekhar Mosuru** — Senior Engineering Lead, 13+ years in distributed systems and FinTech

- LinkedIn: [rajasekhar-mosuru](https://www.linkedin.com/in/rajasekhar-mosuru-04783861/)
- Email: mosururajasekhar@gmail.com

*Built with patterns from real-world payment systems processing 2M+ transactions/day.*
