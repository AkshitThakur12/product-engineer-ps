package com.example.reminders.dto;

import com.example.reminders.model.AttemptStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response representation of a single delivery attempt.
 */
public record DeliveryAttemptResponse(
        UUID deliveryAttemptId,
        UUID scheduledWorkId,
        Integer attemptNumber,
        String deliveryKey,
        AttemptStatus status,
        String errorMessage,
        Instant attemptedAt
) {}
