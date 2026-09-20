package com.example.reminders.entity;

import com.example.reminders.model.AttemptStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing one delivery attempt for a scheduled work item.
 *
 * <p>Every delivery attempt is recorded as a new row — history is never overwritten.
 */
@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {

    @Id
    @Column(name = "delivery_attempt_id", nullable = false, updatable = false)
    private UUID deliveryAttemptId;

    @Column(name = "scheduled_work_id", nullable = false)
    private UUID scheduledWorkId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "delivery_key", nullable = false)
    private String deliveryKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AttemptStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected DeliveryAttempt() {
        // JPA
    }

    public DeliveryAttempt(UUID scheduledWorkId, Integer attemptNumber,
                           String deliveryKey, AttemptStatus status,
                           String errorMessage, Instant attemptedAt) {
        this.deliveryAttemptId = UUID.randomUUID();
        this.scheduledWorkId = scheduledWorkId;
        this.attemptNumber = attemptNumber;
        this.deliveryKey = deliveryKey;
        this.status = status;
        this.errorMessage = errorMessage;
        this.attemptedAt = attemptedAt;
    }

    // ---- Getters ----

    public UUID getDeliveryAttemptId() {
        return deliveryAttemptId;
    }

    public UUID getScheduledWorkId() {
        return scheduledWorkId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public String getDeliveryKey() {
        return deliveryKey;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
