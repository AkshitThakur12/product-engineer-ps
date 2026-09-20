package com.example.reminders.exception;

public class InvalidTimeZoneException extends RuntimeException {

    public InvalidTimeZoneException(String timeZone) {
        super("Invalid IANA time zone: " + timeZone);
    }
}
