package com.example.reminders.model;

/**
 * Outcome status for a single delivery attempt.
 */
public enum AttemptStatus {
    /** Notification was successfully delivered. */
    SUCCESS,

    /** A transient failure that may succeed on retry. */
    TEMPORARY_FAILURE,

    /** A permanent failure that should not be retried. */
    PERMANENT_FAILURE,

    /** Duplicate delivery detected — no logical notification sent. */
    DUPLICATE
}
