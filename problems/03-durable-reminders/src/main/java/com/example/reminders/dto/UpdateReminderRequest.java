package com.example.reminders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating an existing reminder.
 *
 * @param content       the updated reminder content
 * @param localDateTime the updated local date-time in ISO format
 * @param timeZone      the updated IANA time zone identifier
 */
public record UpdateReminderRequest(
        @NotBlank(message = "Content must not be blank")
        String content,

        @NotNull(message = "Local date-time must be provided")
        String localDateTime,

        @NotBlank(message = "Time zone must not be blank")
        String timeZone
) {}
