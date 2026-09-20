package com.example.reminders.controller;

import com.example.reminders.dto.CreateReminderRequest;
import com.example.reminders.dto.ReminderResponse;
import com.example.reminders.dto.UpdateReminderRequest;
import com.example.reminders.service.DueWorkService;
import com.example.reminders.service.ReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing durable reminders and follow-ups.
 */
@RestController
@RequestMapping("/api/v1/reminders")
@Tag(name = "Reminders", description = "Durable Reminders and Scheduled Follow-Ups API")
public class ReminderController {

    private final ReminderService reminderService;
    private final DueWorkService dueWorkService;

    public ReminderController(ReminderService reminderService, DueWorkService dueWorkService) {
        this.reminderService = reminderService;
        this.dueWorkService = dueWorkService;
    }

    @PostMapping
    @Operation(summary = "Create a new reminder",
               description = "Schedules a new reminder with content, local date-time, and IANA time zone.")
    public ResponseEntity<ReminderResponse> createReminder(
            @Valid @RequestBody CreateReminderRequest request) {
        ReminderResponse response = reminderService.createReminder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{scheduledWorkId}")
    @Operation(summary = "Get a reminder",
               description = "Returns the current state of a reminder and its delivery attempt history.")
    public ResponseEntity<ReminderResponse> getReminder(
            @PathVariable UUID scheduledWorkId) {
        ReminderResponse response = reminderService.getReminder(scheduledWorkId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{scheduledWorkId}")
    @Operation(summary = "Update a reminder",
               description = "Updates the content and/or scheduled time of a reminder. " +
                             "Increments the version and regenerates the delivery key. " +
                             "Only allowed before successful delivery.")
    public ResponseEntity<ReminderResponse> updateReminder(
            @PathVariable UUID scheduledWorkId,
            @Valid @RequestBody UpdateReminderRequest request) {
        ReminderResponse response = reminderService.updateReminder(scheduledWorkId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{scheduledWorkId}/cancel")
    @Operation(summary = "Cancel a reminder",
               description = "Cancels a scheduled or running reminder. " +
                             "Cancelled reminders will not produce any future notifications.")
    public ResponseEntity<ReminderResponse> cancelReminder(
            @PathVariable UUID scheduledWorkId) {
        ReminderResponse response = reminderService.cancelReminder(scheduledWorkId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/process-due")
    @Operation(summary = "Process due work",
               description = "Triggers due-work processing manually. Uses the same service " +
                             "as the background scheduler. Returns the count of items processed.")
    public ResponseEntity<Map<String, Object>> processDueWork() {
        int processed = dueWorkService.processDueWork();
        return ResponseEntity.ok(Map.of(
                "processed", processed,
                "message", processed + " due work items processed"
        ));
    }
}
