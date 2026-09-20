# Product Engineering Challenge Submission

## Candidate

- **Name:** Akshit Thakur
- **Email:** akshitthakur2002@gmail.com
- **GitHub:** https://github.com/AkshitThakur12/product-engineer-ps.git
- **Selected problem:** 03 - Durable Reminders and Scheduled Follow-Ups
- **Demo video:** https://drive.google.com/file/d/1WRq8aiScg7vukAaeyv9HlrhlwepGt9O5/view?usp=sharing
## Run the project

### Prerequisites
- **Java 21**
- **PostgreSQL** running locally on port 5432 (or via Docker)

### 1. Start PostgreSQL

**Option A: Using Docker Compose (Recommended)**
```bash
cd problems/03-durable-reminders
docker-compose up -d
```

**Option B: Using Docker Run**
```bash
docker run -d --name reminders-postgres \
  -e POSTGRES_DB=reminders_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

*(Note: If running against your own PostgreSQL instance with custom credentials, set environment variables `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` before running).*

### 2. Run the application
```powershell
cd problems/03-durable-reminders
.\gradlew.bat bootRun
```

The application starts on `http://localhost:8080`.
Interactive Swagger UI / OpenAPI docs: `http://localhost:8080/swagger-ui.html`

### 3. Triggering Scenarios
- **Successful Scenario:**
  Create a reminder due now or in the past:
  ```bash
  curl -X POST http://localhost:8080/api/v1/reminders \
    -H "Content-Type: application/json" \
    -d '{"content": "Meeting reminder", "localDateTime": "2026-09-20T10:00:00", "timeZone": "Asia/Kolkata"}'
  ```
  The background scheduler (every 5 seconds) discovers and claims the reminder, delivers it via `FakeNotificationDestination`, records the attempt in `delivery_attempts`, and transitions the state to `DELIVERED`.

- **Failure & Recovery Scenario:**
  1. Temporary failure: If delivery fails temporarily, the system reschedules for retry with bounded backoff delays (0s, 10s, 30s) up to 3 attempts. If exhausted, it transitions to `FAILED`.
  2. Crash / Restart recovery: If a worker process dies while a reminder is in `RUNNING` state, upon reboot `DueWorkService.recoverStuckWork()` (triggered via `ApplicationReadyEvent`) resets any orphaned `RUNNING` work items back to `SCHEDULED`, allowing them to be claimed and delivered safely without data loss.

## Run the tests

```powershell
cd problems/03-durable-reminders
.\gradlew.bat test
```

To run individual test classes:
```powershell
# Run integration test suite
.\gradlew.bat test --tests "com.example.reminders.DurableRemindersIntegrationTest"

# Run verification benchmark
.\gradlew.bat test --tests "com.example.reminders.VerificationBenchmarkTest"
```

## Acceptance scenarios and verification

All required acceptance scenarios are fully implemented:
1. **Durable Scheduling:** Persisted in PostgreSQL as the source of truth, converted to UTC `Instant` from IANA time zones.
2. **Time Zone & DST Correctness:** Standard conversions (`Asia/Kolkata`, `America/New_York`), spring-forward gap jump (2:30 AM non-existent shifts to 3:30 AM EDT), and fall-back ambiguous overlap (1:30 AM resolves to earlier EDT offset).
3. **Due-Work Discovery & Atomic Claiming:** Polling query with composite index `(state, next_attempt_at)` + atomic conditional UPDATE claim query (`WHERE state = 'SCHEDULED'`).
4. **Bounded Retries & Terminal Failure:** Configurable retry delays (0s, 10s, 30s) up to max 3 attempts. Temporary failures retry; permanent failures immediately transition to `FAILED`.
5. **Idempotent Delivery:** Versioned delivery key (`{scheduledWorkId}:{version}`) ensuring duplicate worker dispatches produce exactly 1 logical notification.
6. **Safe Editing & Cancellation Races:** Optimistic version checking prevents workers from delivering stale versions when users edit or cancel concurrently.
7. **Restart Recovery:** Automatic recovery of orphaned `RUNNING` jobs on application startup.

### Problem-Specific Verification Benchmark

Command to run the benchmark:
```powershell
cd problems/03-durable-reminders
.\gradlew.bat test --tests "com.example.reminders.VerificationBenchmarkTest" --info
```

### Observed Results
```text
=== Verification Benchmark Results ===
Total items:               21
DELIVERED items:           17
CANCELLED items:           2
FAILED items:              2
SCHEDULED items:           0
Logical notifications sent: 17
======================================
```
- **Total items:** 21 items across 2 IANA time zones (`Asia/Kolkata` and `America/New_York`).
- **Terminal States:** 17 DELIVERED, 2 CANCELLED (cancelled before execution), 2 FAILED (1 permanent failure, 1 retry exhaustion).
- **Exact Logical Delivery:** 17 unique logical notifications delivered to the destination. Duplicate worker executions and stale-version deliveries produced 0 extra notifications.

### Failure & Recovery Demo
In the demo video:
1. A reminder is claimed into `RUNNING` state.
2. A crash/restart is simulated.
3. On restart, the application recovers the `RUNNING` item back to `SCHEDULED` and processes it to `DELIVERED`.
4. Stale-version execution race is shown where an edit increments the version from `1` to `2`, causing the worker holding version `1` to abort without delivering stale content.

## Architecture and data flow

```
[Client / REST API]
       │
       ▼
[ReminderController]
       │
       ▼
[ReminderService] ─── (CRUD, Zone conversion, Version management)
       │
       ▼
[PostgreSQL Database] ─── (scheduled_work & delivery_attempts tables)
       ▲
       │ (Poll due work & atomic claim)
[DueWorkService] ◄─── [Background Scheduler (5s interval)]
       │
       ▼ (Dispatch)
[DeliveryService] ─── (Race pre-check, retry handling, status updates)
       │
       ▼
[NotificationDestination] (Idempotent delivery boundary via delivery_key)
```

## Technology choices

- **Java 21 & Spring Boot 3.3.5:** Modern, LTS enterprise runtime with built-in scheduling, declarative transactions, and Spring Data JPA.
- **PostgreSQL:** Reliable ACID relational database with transactional indexing for due-work discovery (`(state, next_attempt_at)`) and atomic updates.
- **Springdoc OpenAPI / Swagger UI:** Auto-generated interactive API documentation for testing.
- **JUnit 5 & Mockito with Controllable Clock:** Eliminates flaky `Thread.sleep` calls by injecting an adjustable `Clock` reference for deterministic time testing across DST boundaries.

## Important decisions and design principles

### 1. How local time and time zones become an execution instant
Local date-time and IANA time zone strings are parsed and mapped to a UTC `java.time.Instant` at creation/edit time:
- Uses `localDateTime.atZone(zoneId).withEarlierOffsetAtOverlap()`.
- **Ambiguous time (DST fall-back):** Resolves to the earlier offset (e.g., 1:30 AM in `America/New_York` resolves to EDT UTC-4 = 05:30 UTC).
- **Nonexistent time (DST spring-forward):** Shifts forward by the gap length to the next valid local time (e.g., 2:30 AM gap shifts to 3:30 AM EDT = 07:30 UTC).
- The calculated `scheduled_at` UTC `Instant` is indexed and queried directly against `now()`, while the original `time_zone` string is retained for display and auditing.

### 2. How due work is discovered and claimed
- **Discovery:** A database query finds items matching `state = 'SCHEDULED' AND next_attempt_at <= :now` ordered by `next_attempt_at ASC`.
- **Composite Index:** An index on `(state, next_attempt_at)` enables index-scan efficiency without full table scans.
- **Atomic Claiming:** A worker executes a conditional update: `UPDATE scheduled_work SET state = 'RUNNING', updated_at = :now WHERE scheduled_work_id = :id AND state = 'SCHEDULED' AND version = :version`. If `rowsUpdated == 1`, the worker owns the item; otherwise, it was claimed or modified by another process and is skipped.

### 3. Which failures are retryable and why
- **Temporary Failures (Retryable):** Downstream service timeouts, rate limits (HTTP 429/503), transient network disconnects. These indicate the destination may recover.
- **Permanent Failures (Non-retryable):** Invalid recipient addresses, malformed content, 4xx client errors (e.g., 400 Bad Request, 404 Not Found). These are immediately transitioned to `FAILED` without wasting worker cycles on hopeless retries.

### 4. The retry limit and delay policy
- **Configurable Limit:** Max 3 attempts (`app.retry.max-attempts=3`).
- **Delay Schedule:** Configurable intervals `0s, 10s, 30s` (`app.retry.delays-seconds`).
- Attempt 1 is immediate; attempt 2 is scheduled at `now + 10s`; attempt 3 at `now + 30s`. After 3 failed attempts, state transitions to `FAILED`.

### 5. What creates a unique scheduled occurrence
A unique occurrence is identified by `scheduled_work_id`. Each content/time edit increments the `version` (1, 2, 3...), generating a unique `delivery_key` = `{scheduled_work_id}:{version}`.

### 6. How idempotency is enforced at the delivery boundary
- The `NotificationDestination` receives the `delivery_key` with every dispatch.
- Downstream destinations deduplicate on `delivery_key`. If the same key is received more than once, it returns `DUPLICATE`, preventing multiple customer-facing notifications while allowing the worker to safely mark the item as `DELIVERED`.

### 7. The edit/cancellation race policy
- **Pre-Check:** `DeliveryService` re-reads the database state immediately before calling the notification destination. If `state == CANCELLED` or the database `version != claimedVersion`, the delivery is aborted before sending.
- **Post-Check:** `DeliveryService` re-checks the database version before updating state to `DELIVERED`. If an edit/cancellation occurred during the external network call, the stale worker aborts its state transition.
- **Rule:** Cancellation or edit committed before delivery commits always wins over in-flight stale dispatches.

### 8. What guarantees change with multiple workers
- **Single-Worker Mode:** Strict FIFO ordering by `next_attempt_at`.
- **Multi-Worker Mode:** The atomic conditional `claimWork` query ensures no two workers can claim the same item. However:
  1. Strict arrival ordering is loose across concurrent workers executing at the same second.
  2. Workers claiming different items operate concurrently.
  3. The `delivery_key` idempotency boundary guarantees at-most-once logical delivery even in the presence of redundant worker retries or crash recovery.


## Assumptions and limitations

- Single node / light multi-worker deployment: uses DB polling indexing. For thousands of events per second, database partitioning or message streaming would be considered.
- Fake notification destination simulates downstream webhooks/email/SMS.

## Production and scale

If scaling to millions of reminders:
1. **Partitioning / Sharding:** Partition `scheduled_work` table by `next_attempt_at` time buckets.
2. **Skip Locked Fetching:** Use PostgreSQL `FOR UPDATE SKIP LOCKED` for concurrent multi-instance worker pools.
3. **Dead Letter Queue:** Move exhausted `FAILED` items to an audit DLQ for alerting.

## AI usage

AI assistance (Antigravity) was used for initial scaffolding, test scenario generation (DST boundaries, concurrency races), and documentation structuring. All generated code, database schemas, and business logic were reviewed, verified against the problem specification, and validated through 18 automated integration tests.

## Credibility note

[Add your background/credibility note here according to the template]
