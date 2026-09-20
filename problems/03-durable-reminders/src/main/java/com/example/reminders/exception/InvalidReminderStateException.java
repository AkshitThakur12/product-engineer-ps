package com.example.reminders.exception;

public class InvalidReminderStateException extends RuntimeException {

    public InvalidReminderStateException(String message) {
        super(message);
    }
}
