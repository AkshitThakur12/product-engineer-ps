package com.example.reminders.model;

/**
 * Result of a delivery attempt from the notification destination.
 *
 * @param status       the outcome status
 * @param errorMessage optional error message (null on success)
 */
public record DeliveryResult(
        AttemptStatus status,
        String errorMessage
) {
    public static DeliveryResult success() {
        return new DeliveryResult(AttemptStatus.SUCCESS, null);
    }

    public static DeliveryResult temporaryFailure(String message) {
        return new DeliveryResult(AttemptStatus.TEMPORARY_FAILURE, message);
    }

    public static DeliveryResult permanentFailure(String message) {
        return new DeliveryResult(AttemptStatus.PERMANENT_FAILURE, message);
    }

    public static DeliveryResult duplicate() {
        return new DeliveryResult(AttemptStatus.DUPLICATE, "Duplicate delivery key");
    }
}
