# Durable Reminders and Scheduled Follow-Ups

A small, maintainable service for scheduling reminders and follow-ups that survives process restarts, handles retries with bounded policies, enforces idempotent delivery, and correctly converts time zones including daylight-saving boundaries.

## Project Overview

This service allows creating scheduled reminders/follow-ups with:
- **IANA time zone support** — schedules are specified in local time with a named zone
- **Durable scheduling** — the database is the source of truth, not in-memory timers
- **Restart recovery** — overdue work is discovered and processed after restarts
- **Bounded retry** — temporary delivery failures are retried up to a configurable limit
- **Idempotent delivery** — duplicate execution never produces duplicate logical notifications
- **Safe editing & cancellation** — version-based race detection prevents stale deliveries

## Architecture

```
REST API (ReminderController)
    ↓
Service Layer
    ├── ReminderService      — CRUD, timezone conversion, state validation
    ├── DueWorkService       — discovery, claiming, restart recovery
    └── DeliveryService      — delivery attempts, retry logic, race detection
            ↓
Repository Layer
    ├── ScheduledWorkRepository    — due-work queries, conditional claim
    └── DeliveryAttemptRepository  — attempt history
            ↓
PostgreSQL (source of truth)

Background Scheduler (SchedulerConfig)
    ↓
DueWorkService.processDueWork()
    ↓
DeliveryService.attemptDelivery()
    ↓
FakeNotificationDestination (idempotent delivery boundary)
```

## Setup

### Prerequisites
- Java 21
- Docker (for PostgreSQL)
- Gradle (wrapper included)

### 1. Start PostgreSQL

```bash
docker-compose up -d
```

This starts PostgreSQL on port 5432 with:
- Database: `reminders_db`
- User: `postgres`
- Password: `postgres`

### 2. Configure Environment Variables (optional)

The defaults in `application.yml` match the docker-compose configuration. Override if needed:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reminders_db
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
```

### 3. Build and Run

```bash
# Build
./gradlew build

# Run
./gradlew bootRun
```

### 4. Open Swagger UI

Navigate to: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## REST API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/reminders` | Create a new reminder |
| GET | `/api/v1/reminders/{id}` | Get reminder with attempt history |
| PUT | `/api/v1/reminders/{id}` | Update content/schedule (increments version) |
| POST | `/api/v1/reminders/{id}/cancel` | Cancel a reminder |
| POST | `/api/v1/reminders/process-due` | Manually trigger due-work processing |

### Create Reminder Example

```json
POST /api/v1/reminders
{
  "content": "Call Mom",
  "localDateTime": "2026-09-20T18:00:00",
  "timeZone": "Asia/Kolkata"
}
```

## Testing

```bash
./gradlew test
```

Tests use H2 in-memory database and an injectable Clock — no Docker, no real-time waits.

### Test Coverage

| # | Test | Description |
|---|------|-------------|
| 1 | Due-work discovery | Verifies work is found only when clock reaches scheduled time |
| 2 | Restart recovery | Simulates overdue work discovered after restart |
| 3 | Temporary failure + retry | First attempt fails, retry succeeds |
| 4 | Retry exhaustion | All 3 attempts fail → FAILED state |
| 5 | Duplicate execution | Same occurrence executed twice → one logical notification |
| 6 | Edit before execution | Version incremented, old delivery key invalidated |
| 7 | Cancellation before execution | Cancelled work never produces notifications |
| 8 | Edit-vs-execution race | Worker with stale version detects mismatch |
| 9 | Cancel-vs-execution race | Cancellation wins before delivery commits |
| 10 | Asia/Kolkata timezone | 18:00 IST → 12:30 UTC |
| 11 | America/New_York timezone | 14:00 EDT → 18:00 UTC |
| 12 | DST spring-forward | Nonexistent 2:30 AM → shifted to 3:00 AM |
| 12b | DST fall-back | Ambiguous 1:30 AM → earlier offset chosen |

## Verification Benchmark

```bash
./gradlew test --tests "com.example.reminders.VerificationBenchmarkTest"
```

The benchmark:
1. Creates 21 items across Asia/Kolkata and America/New_York
2. Includes delivered, edited, cancelled, temp-failing, and perm-failing items
3. Simulates restart recovery (RUNNING → SCHEDULED)
4. Simulates duplicate execution for one occurrence
5. Advances the injectable clock through multiple processing cycles
6. Reports counts by terminal state
7. Verifies every delivered occurrence produced exactly one logical notification

## Design Decisions

### Why is `scheduled_at` an Instant?

An `Instant` is an unambiguous point on the timeline. Polling compares `scheduled_at <= clock.instant()` without any timezone conversion. The IANA zone is retained separately for display and audit purposes.

### Why retain `time_zone`?

The original timezone is preserved for:
- Display to the user in their local time
- Audit trail
- Potential rescheduling in the same zone

### Timezone Conversion Policy

| Scenario | Policy |
|----------|--------|
| Normal local time | Converted normally |
| Ambiguous (DST fall-back) | **Earlier offset** chosen |
| Nonexistent (DST spring-forward) | **Shifted forward** to next valid local time |

Uses Java's `ZonedDateTime.atZone().withEarlierOffsetAtOverlap()`.

### Why use `version`?

The version field serves two purposes:
1. **Delivery key generation** — `deliveryKey = {id}:{version}` ensures edits invalidate prior delivery keys
2. **Race detection** — workers compare their claimed version against the current DB version before committing delivery

### Why use `delivery_key`?

The delivery key (`{scheduledWorkId}:{version}`) is the idempotency key at the notification boundary. The `FakeNotificationDestination` tracks which keys have been successfully delivered and returns DUPLICATE for repeated attempts with the same key.

### Why is the database the source of truth?

In-memory scheduled tasks are lost on process restart. By storing all state in PostgreSQL:
- Overdue work is discovered via a simple query: `scheduled_at <= now`
- No work is lost on restart
- State is inspectable and auditable

### Restart Recovery

On application startup:
1. Find all work in RUNNING state
2. Reset to SCHEDULED (these were from a crashed worker)
3. Make immediately eligible for rediscovery

Additionally, the due-work query naturally finds overdue SCHEDULED work because `scheduled_at <= current_time` is true for past times.

### Retry Policy

| Attempt | Delay |
|---------|-------|
| 1 | Immediate (0s) |
| 2 | +10 seconds |
| 3 | +30 seconds |

- Maximum 3 attempts (configurable via `app.retry.max-attempts`)
- Only **temporary failures** are retried
- **Permanent failures** go directly to FAILED
- After retry exhaustion → FAILED

Why temporary vs permanent?
- **Temporary**: service unavailable, network timeout, rate limit — may succeed later
- **Permanent**: invalid recipient, malformed content — will never succeed

### Idempotency

The delivery boundary (NotificationDestination) uses the delivery key as the idempotency key:
- First successful delivery with key `ABC:1` → notification sent
- Second delivery with key `ABC:1` → returns DUPLICATE, no notification

This provides one logical delivery per delivery key within the implemented boundary. This is NOT distributed exactly-once — it's a single-process guarantee backed by the fake destination's in-memory state and the database's delivery attempt history.

### Edit Race Policy

If a user edits a reminder while a worker is processing the old version:

```
Worker claims version 1 → RUNNING
User edits → version 2, delivery key changes
Worker attempts delivery with version 1
Worker re-reads DB → detects version mismatch (1 ≠ 2)
Worker abandons delivery → no stale notification
```

### Cancellation Race Policy

> **Cancellation wins if committed before delivery commits.**

```
Worker claims → RUNNING
User cancels → CANCELLED
Worker re-reads DB → detects CANCELLED state
Worker abandons → no notification recorded
```

### Multi-Worker Limitations

The conditional claim (`UPDATE ... WHERE state='SCHEDULED' AND version=expected`) prevents two workers from both claiming the same item. However:
- With a single process, this is straightforward
- With multiple processes, the database conditional update provides safety, but no distributed coordination is implemented
- The idempotent delivery boundary provides an additional safety net

## Package Structure

```
src/main/java/com/example/reminders/
├── DurableRemindersApplication.java
├── config/
│   ├── ClockConfig.java          # Injectable Clock bean
│   ├── SchedulerConfig.java      # Background scheduler + restart recovery
│   └── OpenApiConfig.java        # Swagger/OpenAPI metadata
├── controller/
│   └── ReminderController.java   # REST API endpoints
├── dto/
│   ├── CreateReminderRequest.java
│   ├── UpdateReminderRequest.java
│   ├── ReminderResponse.java
│   └── DeliveryAttemptResponse.java
├── entity/
│   ├── ScheduledWork.java        # Main JPA entity
│   └── DeliveryAttempt.java      # Delivery history entity
├── exception/
│   ├── GlobalExceptionHandler.java
│   ├── InvalidReminderStateException.java
│   ├── InvalidTimeZoneException.java
│   └── ReminderNotFoundException.java
├── model/
│   ├── AttemptStatus.java        # SUCCESS, TEMPORARY_FAILURE, etc.
│   ├── DeliveryResult.java       # Result record from destination
│   └── WorkState.java            # SCHEDULED, RUNNING, DELIVERED, etc.
├── notification/
│   ├── NotificationDestination.java      # Interface
│   └── FakeNotificationDestination.java  # Test/demo implementation
├── repository/
│   ├── ScheduledWorkRepository.java
│   └── DeliveryAttemptRepository.java
└── service/
    ├── ReminderService.java      # CRUD + timezone logic
    ├── DueWorkService.java       # Discovery + claiming + recovery
    └── DeliveryService.java      # Delivery + retry + race handling
```
