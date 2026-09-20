package com.example.reminders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating a new reminder.
 *
 * @param content       the reminder content (must not be blank)
 * @param localDateTime the local date-time in ISO format (e.g. "2026-09-20T18:00:00")
 * @param timeZone      IANA time zone identifier (e.g. "Asia/Kolkata")
 */
public record CreateReminderRequest(
        @NotBlank(message = "Content must not be blank")
        String content,

        @NotNull(message = "Local date-time must be provided")
        String localDateTime,

        @NotBlank(message = "Time zone must not be blank")
        String timeZone
) {}
