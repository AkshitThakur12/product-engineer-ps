package com.example.reminders.dto;

import com.example.reminders.entity.DeliveryAttempt;
import com.example.reminders.entity.ScheduledWork;
import com.example.reminders.model.WorkState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response representation of a scheduled reminder with its attempt history.
 */
public record ReminderResponse(
        UUID scheduledWorkId,
        String content,
        Instant scheduledAt,
        String timeZone,
        WorkState state,
        Long version,
        String deliveryKey,
        Integer attemptCount,
        Instant createdAt,
        Instant updatedAt,
        List<DeliveryAttemptResponse> attempts
) {
    /**
     * Creates a ReminderResponse from entity and attempt history.
     */
    public static ReminderResponse from(ScheduledWork work, List<DeliveryAttempt> attempts) {
        List<DeliveryAttemptResponse> attemptResponses = attempts.stream()
                .map(a -> new DeliveryAttemptResponse(
                        a.getDeliveryAttemptId(),
                        a.getScheduledWorkId(),
                        a.getAttemptNumber(),
                        a.getDeliveryKey(),
                        a.getStatus(),
                        a.getErrorMessage(),
                        a.getAttemptedAt()
                ))
                .toList();

        return new ReminderResponse(
                work.getScheduledWorkId(),
                work.getContent(),
                work.getScheduledAt(),
                work.getTimeZone(),
                work.getState(),
                work.getVersion(),
                work.getDeliveryKey(),
                work.getAttemptCount(),
                work.getCreatedAt(),
                work.getUpdatedAt(),
                attemptResponses
        );
    }
}
