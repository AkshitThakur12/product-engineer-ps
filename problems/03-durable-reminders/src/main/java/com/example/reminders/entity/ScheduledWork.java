package com.example.reminders.entity;

import com.example.reminders.model.WorkState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing one scheduled reminder or follow-up occurrence.
 *
 * <p>The database is the source of truth. The version field is used
 * to generate a deterministic delivery key ({scheduledWorkId}:{version})
 * so that edits invalidate prior versions.
 */
@Entity
@Table(name = "scheduled_work")
public class ScheduledWork {

    @Id
    @Column(name = "scheduled_work_id", nullable = false, updatable = false)
    private UUID scheduledWorkId;

    @Column(name = "content", nullable = false)
    private String content;

    /** The UTC instant at which this work is due for execution. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** The IANA time zone of the original request (e.g. "Asia/Kolkata"). */
    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private WorkState state;

    /**
     * Logical version, incremented on each content/schedule edit.
     * Used to form the delivery key and to detect stale worker execution.
     */
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Deterministic delivery key = "{scheduledWorkId}:{version}".
     * Used as the idempotency key at the notification boundary.
     */
    @Column(name = "delivery_key", nullable = false, unique = true)
    private String deliveryKey;

    /**
     * The next instant at which this work is eligible for execution.
     * Equals scheduledAt initially; updated on retries.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScheduledWork() {
        // JPA
    }

    /**
     * Creates a new ScheduledWork in SCHEDULED state.
     */
    public ScheduledWork(UUID scheduledWorkId, String content, Instant scheduledAt,
                         String timeZone, Instant now) {
        this.scheduledWorkId = scheduledWorkId;
        this.content = content;
        this.scheduledAt = scheduledAt;
        this.timeZone = timeZone;
        this.state = WorkState.SCHEDULED;
        this.version = 1L;
        this.deliveryKey = scheduledWorkId + ":" + 1;
        this.nextAttemptAt = scheduledAt;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ---- Getters ----

    public UUID getScheduledWorkId() {
        return scheduledWorkId;
    }

    public String getContent() {
        return content;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public WorkState getState() {
        return state;
    }

    public Long getVersion() {
        return version;
    }

    public String getDeliveryKey() {
        return deliveryKey;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ---- State mutation methods ----

    public void setState(WorkState state) {
        this.state = state;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setDeliveryKey(String deliveryKey) {
        this.deliveryKey = deliveryKey;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Increments the version and regenerates the delivery key.
     * Call this whenever content or scheduling is edited.
     */
    public void incrementVersion() {
        this.version++;
        this.deliveryKey = this.scheduledWorkId + ":" + this.version;
    }

    /**
     * Generates the delivery key from current id and version.
     */
    public String computeDeliveryKey() {
        return this.scheduledWorkId + ":" + this.version;
    }
}
