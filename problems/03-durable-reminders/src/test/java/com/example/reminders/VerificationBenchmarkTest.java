package com.example.reminders;

import com.example.reminders.dto.CreateReminderRequest;
import com.example.reminders.dto.ReminderResponse;
import com.example.reminders.dto.UpdateReminderRequest;
import com.example.reminders.model.AttemptStatus;
import com.example.reminders.model.DeliveryResult;
import com.example.reminders.model.WorkState;
import com.example.reminders.notification.FakeNotificationDestination;
import com.example.reminders.repository.DeliveryAttemptRepository;
import com.example.reminders.repository.ScheduledWorkRepository;
import com.example.reminders.service.DeliveryService;
import com.example.reminders.service.DueWorkService;
import com.example.reminders.service.ReminderService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification benchmark: creates 20+ scheduled items across 2+ IANA time zones,
 * exercises all terminal states, simulates restart recovery and duplicate execution,
 * and verifies the one-logical-notification invariant.
 *
 * <p>This is a deterministic workflow-correctness benchmark, not a throughput target.
 * All time is controlled via an injectable Clock. No Thread.sleep() is used.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerificationBenchmarkTest {

    static final AtomicReference<Clock> CLOCK_REF =
            new AtomicReference<>(Clock.fixed(
                    Instant.parse("2026-09-20T08:00:00Z"),
                    ZoneOffset.UTC));

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public Clock testClock() {
            return new Clock() {
                @Override
                public ZoneId getZone() { return CLOCK_REF.get().getZone(); }
                @Override
                public Clock withZone(ZoneId zone) { return CLOCK_REF.get().withZone(zone); }
                @Override
                public Instant instant() { return CLOCK_REF.get().instant(); }
            };
        }
    }

    @Autowired private ReminderService reminderService;
    @Autowired private DueWorkService dueWorkService;
    @Autowired private DeliveryService deliveryService;
    @Autowired private ScheduledWorkRepository workRepository;
    @Autowired private DeliveryAttemptRepository attemptRepository;
    @Autowired private FakeNotificationDestination fakeDestination;

    @BeforeEach
    void setUp() {
        CLOCK_REF.set(Clock.fixed(Instant.parse("2026-09-20T08:00:00Z"), ZoneOffset.UTC));
        fakeDestination.reset();
        attemptRepository.deleteAll();
        workRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("Verification Benchmark: 20+ items, 2 zones, all states, restart, duplicates")
    void verificationBenchmark() {
        // ================================================================
        // Step 1: Create 20+ scheduled items across 2 IANA time zones
        // ================================================================
        setClock("2026-09-20T08:00:00Z");

        List<UUID> allIds = new ArrayList<>();
        List<UUID> normalDeliveryIds = new ArrayList<>();
        List<UUID> editedIds = new ArrayList<>();
        List<UUID> cancelledIds = new ArrayList<>();
        List<UUID> tempFailIds = new ArrayList<>();
        List<UUID> permFailIds = new ArrayList<>();
        UUID duplicateTestId = null;

        // 8 normal deliveries — Asia/Kolkata (14:00 IST = 08:30 UTC)
        for (int i = 1; i <= 8; i++) {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Normal IST #" + i,
                            "2026-09-20T14:00:00",
                            "Asia/Kolkata"));
            allIds.add(r.scheduledWorkId());
            normalDeliveryIds.add(r.scheduledWorkId());
        }

        // 4 normal deliveries — America/New_York (12:30 EDT = 16:30 UTC)
        for (int i = 1; i <= 4; i++) {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Normal EDT #" + i,
                            "2026-09-20T12:30:00",
                            "America/New_York"));
            allIds.add(r.scheduledWorkId());
            normalDeliveryIds.add(r.scheduledWorkId());
        }

        // 2 items to be edited
        for (int i = 1; i <= 2; i++) {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Edit me #" + i,
                            "2026-09-20T14:00:00",
                            "Asia/Kolkata"));
            allIds.add(r.scheduledWorkId());
            editedIds.add(r.scheduledWorkId());
        }

        // 2 items to be cancelled
        for (int i = 1; i <= 2; i++) {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Cancel me #" + i,
                            "2026-09-20T14:00:00",
                            "Asia/Kolkata"));
            allIds.add(r.scheduledWorkId());
            cancelledIds.add(r.scheduledWorkId());
        }

        // 2 items that will temporarily fail then succeed
        for (int i = 1; i <= 2; i++) {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Temp fail #" + i,
                            "2026-09-20T14:00:00",
                            "Asia/Kolkata"));
            allIds.add(r.scheduledWorkId());
            tempFailIds.add(r.scheduledWorkId());
            // Configure first attempt to fail
            fakeDestination.configureBehavior(r.deliveryKey(),
                    DeliveryResult.temporaryFailure("Transient error"));
        }

        // 2 items that will permanently fail
        for (int i = 1; i <= 2; i++) {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Perm fail #" + i,
                            "2026-09-20T14:00:00",
                            "Asia/Kolkata"));
            allIds.add(r.scheduledWorkId());
            permFailIds.add(r.scheduledWorkId());
            fakeDestination.configureBehavior(r.deliveryKey(),
                    DeliveryResult.permanentFailure("Invalid recipient"));
        }

        // 1 item for duplicate execution test
        {
            ReminderResponse r = reminderService.createReminder(
                    new CreateReminderRequest(
                            "Duplicate test",
                            "2026-09-20T14:00:00",
                            "Asia/Kolkata"));
            allIds.add(r.scheduledWorkId());
            normalDeliveryIds.add(r.scheduledWorkId());
            duplicateTestId = r.scheduledWorkId();
        }

        assertEquals(21, allIds.size(), "Should have created 21 items");

        // ================================================================
        // Step 2: Edit items before processing
        // ================================================================
        for (UUID id : editedIds) {
            reminderService.updateReminder(id,
                    new UpdateReminderRequest(
                            "Edited content",
                            "2026-09-20T15:00:00",      // 15:00 IST = 09:30 UTC
                            "Asia/Kolkata"));
        }

        // ================================================================
        // Step 3: Cancel items before processing
        // ================================================================
        for (UUID id : cancelledIds) {
            reminderService.cancelReminder(id);
        }

        // ================================================================
        // Step 4: Process first batch (IST items at 08:30 UTC)
        //         But stop before processing EDT items (16:30 UTC)
        // ================================================================
        setClock("2026-09-20T08:30:00Z");
        dueWorkService.processDueWork();

        // At this point: IST normal items should be delivered,
        // temp-fail items should have failed once,
        // perm-fail items should be FAILED,
        // cancelled items should remain CANCELLED,
        // edited items NOT yet due (09:30 UTC),
        // EDT items NOT yet due (16:30 UTC)

        // ================================================================
        // Step 5: Simulate restart recovery
        //         Manually put one normal delivery item to RUNNING (as if process crashed)
        // ================================================================
        // Find a SCHEDULED EDT item and force it to RUNNING to simulate crash
        UUID crashedId = normalDeliveryIds.stream()
                .filter(id -> {
                    ReminderResponse r = reminderService.getReminder(id);
                    return r.state() == WorkState.SCHEDULED; // EDT items are still scheduled
                })
                .findFirst()
                .orElseThrow();

        var crashedWork = workRepository.findById(crashedId).orElseThrow();
        crashedWork.setState(WorkState.RUNNING);
        workRepository.save(crashedWork);

        // Recover stuck work (simulates restart)
        int recovered = dueWorkService.recoverStuckWork();
        assertTrue(recovered >= 1, "Should recover at least 1 stuck item");

        // ================================================================
        // Step 6: Clear temp-fail behaviors so retries succeed
        // ================================================================
        for (UUID id : tempFailIds) {
            ReminderResponse r = reminderService.getReminder(id);
            fakeDestination.clearBehavior(r.deliveryKey());
        }

        // ================================================================
        // Step 7: Advance clock and process retries (temp-fail items)
        // ================================================================
        setClock("2026-09-20T08:30:15Z"); // Past 10s retry delay
        dueWorkService.processDueWork();

        // ================================================================
        // Step 8: Process edited items (due at 09:30 UTC)
        // ================================================================
        setClock("2026-09-20T09:30:00Z");
        dueWorkService.processDueWork();

        // ================================================================
        // Step 9: Simulate duplicate execution for one item
        // ================================================================
        {
            ReminderResponse delivered = reminderService.getReminder(duplicateTestId);
            assertEquals(WorkState.DELIVERED, delivered.state());

            // Force back to RUNNING to simulate duplicate worker
            var dupWork = workRepository.findById(duplicateTestId).orElseThrow();
            dupWork.setState(WorkState.RUNNING);
            workRepository.save(dupWork);

            deliveryService.attemptDelivery(dupWork, delivered.version());

            // Should still be DELIVERED (duplicate detected)
        }

        // ================================================================
        // Step 10: Process EDT items (due at 16:30 UTC)
        // ================================================================
        setClock("2026-09-20T16:30:00Z");
        dueWorkService.processDueWork();

        // ================================================================
        // Step 11: Continue until everything settles
        // ================================================================
        setClock("2026-09-20T17:00:00Z");
        dueWorkService.processDueWork();

        // ================================================================
        // Step 12: Report and verify
        // ================================================================
        int deliveredCount = 0;
        int cancelledCount = 0;
        int failedCount = 0;
        int scheduledCount = 0;

        for (UUID id : allIds) {
            ReminderResponse r = reminderService.getReminder(id);
            switch (r.state()) {
                case DELIVERED -> deliveredCount++;
                case CANCELLED -> cancelledCount++;
                case FAILED -> failedCount++;
                case SCHEDULED -> scheduledCount++;
                default -> fail("Unexpected state: " + r.state() + " for " + id);
            }
        }

        System.out.println("=== Verification Benchmark Results ===");
        System.out.println("Total items:    " + allIds.size());
        System.out.println("DELIVERED:      " + deliveredCount);
        System.out.println("CANCELLED:      " + cancelledCount);
        System.out.println("FAILED:         " + failedCount);
        System.out.println("SCHEDULED:      " + scheduledCount);
        System.out.println("Logical notifications: " + fakeDestination.getDeliveredCount());
        System.out.println("======================================");

        // Verify counts
        // 12 normal + 1 duplicate test + 2 edited + 2 temp-fail = 17 delivered
        assertEquals(17, deliveredCount, "Expected 17 delivered items");
        assertEquals(2, cancelledCount, "Expected 2 cancelled items");
        assertEquals(2, failedCount, "Expected 2 failed items");
        assertEquals(0, scheduledCount, "Expected 0 still scheduled");

        // ================================================================
        // Verify one-logical-notification invariant
        // ================================================================
        // Count how many delivered items have their delivery key in the fake destination
        int logicalNotifications = 0;
        for (UUID id : allIds) {
            ReminderResponse r = reminderService.getReminder(id);
            if (r.state() == WorkState.DELIVERED) {
                assertTrue(fakeDestination.wasDelivered(r.deliveryKey()),
                        "Delivered item " + id + " should have a logical notification");
                logicalNotifications++;
            }
        }

        assertEquals(deliveredCount, logicalNotifications,
                "Each delivered item should have exactly one logical notification");
        assertEquals(deliveredCount, fakeDestination.getDeliveredCount(),
                "Fake destination count should match delivered count (no duplicates)");
    }

    private void setClock(String instant) {
        CLOCK_REF.set(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
