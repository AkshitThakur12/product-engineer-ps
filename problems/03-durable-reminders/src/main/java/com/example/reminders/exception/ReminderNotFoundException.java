package com.example.reminders.exception;

import java.util.UUID;

public class ReminderNotFoundException extends RuntimeException {

    public ReminderNotFoundException(UUID scheduledWorkId) {
        super("Reminder not found: " + scheduledWorkId);
    }
}
