package com.example.reminders;

import com.example.reminders.dto.CreateReminderRequest;
import com.example.reminders.dto.ReminderResponse;
import com.example.reminders.dto.UpdateReminderRequest;
import com.example.reminders.entity.ScheduledWork;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Durable Reminders service.
 *
 * <p>All tests use an injectable Clock (Clock.fixed) for deterministic behavior.
 * No Thread.sleep() is used. The scheduler is disabled; due-work processing
 * is triggered manually via DueWorkService.processDueWork().
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DurableRemindersIntegrationTest {

    /** A mutable clock reference that tests can change to control time. */
    static final AtomicReference<Clock> CLOCK_REF =
            new AtomicReference<>(Clock.fixed(
                    Instant.parse("2026-09-20T10:00:00Z"),
                    ZoneOffset.UTC));

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public Clock testClock() {
            // Returns a Clock that delegates to whatever CLOCK_REF currently holds
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return CLOCK_REF.get().getZone();
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return CLOCK_REF.get().withZone(zone);
                }

                @Override
                public Instant instant() {
                    return CLOCK_REF.get().instant();
                }
            };
        }
    }

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private DueWorkService dueWorkService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private ScheduledWorkRepository workRepository;

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

    @Autowired
    private FakeNotificationDestination fakeDestination;

    @BeforeEach
    void setUp() {
        // Reset clock to baseline
        CLOCK_REF.set(Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC));
        // Reset fake destination
        fakeDestination.reset();
        // Clear database
        attemptRepository.deleteAll();
        workRepository.deleteAll();
    }

    // ========================================================================
    // Test 1: Due-work discovery using injected Clock
    // ========================================================================
    @Test
    @Order(1)
    @DisplayName("Test 1: Due-work discovery using injected Clock")
    void testDueWorkDiscovery() {
        // Create a reminder scheduled at 10:30 UTC
        setClock("2026-09-20T10:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("Call Mom", "2026-09-20T16:00:00", "Asia/Kolkata"));
        // Asia/Kolkata is UTC+5:30, so 16:00 IST = 10:30 UTC

        // At 10:15 UTC — not yet due
        setClock("2026-09-20T10:15:00Z");
        int processed = dueWorkService.processDueWork();
        assertEquals(0, processed, "Should not process work before scheduled time");

        // Verify still SCHEDULED
        ReminderResponse check = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.SCHEDULED, check.state());

        // At 10:30 UTC — exactly due
        setClock("2026-09-20T10:30:00Z");
        processed = dueWorkService.processDueWork();
        assertEquals(1, processed, "Should process due work at scheduled time");

        // Verify DELIVERED
        ReminderResponse delivered = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, delivered.state());
        assertEquals(1, delivered.attempts().size());
        assertEquals(AttemptStatus.SUCCESS, delivered.attempts().get(0).status());

        // Verify exactly one logical notification
        assertTrue(fakeDestination.wasDelivered(delivered.deliveryKey()));
        assertEquals(1, fakeDestination.getDeliveredCount());
    }

    // ========================================================================
    // Test 2: Restart recovery for overdue work
    // ========================================================================
    @Test
    @Order(2)
    @DisplayName("Test 2: Restart recovery for overdue work")
    void testRestartRecovery() {
        // Create a reminder scheduled at 10:00 UTC
        setClock("2026-09-20T09:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("Follow up", "2026-09-20T15:30:00", "Asia/Kolkata"));
        // 15:30 IST = 10:00 UTC

        // Simulate application stopped — skip past 10:00 without processing
        // Application "restarts" at 10:15
        setClock("2026-09-20T10:15:00Z");

        // Due-work discovery should find the overdue item
        int processed = dueWorkService.processDueWork();
        assertEquals(1, processed, "Should discover and process overdue work after restart");

        ReminderResponse result = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, result.state());
    }

    // ========================================================================
    // Test 3: Temporary failure followed by retry
    // ========================================================================
    @Test
    @Order(3)
    @DisplayName("Test 3: Temporary failure followed by retry")
    void testTemporaryFailureAndRetry() {
        setClock("2026-09-20T10:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("Retry test", "2026-09-20T15:30:00", "Asia/Kolkata"));

        // Configure first attempt to fail temporarily
        fakeDestination.configureBehavior(reminder.deliveryKey(),
                DeliveryResult.temporaryFailure("Service unavailable"));

        // Process at 10:00 — should fail and schedule retry
        setClock("2026-09-20T10:00:00Z");
        dueWorkService.processDueWork();

        ReminderResponse afterFail = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.SCHEDULED, afterFail.state(), "Should be back to SCHEDULED for retry");
        assertEquals(1, afterFail.attemptCount());
        assertEquals(1, afterFail.attempts().size());
        assertEquals(AttemptStatus.TEMPORARY_FAILURE, afterFail.attempts().get(0).status());

        // Clear the failure configuration so next attempt succeeds
        fakeDestination.clearBehavior(reminder.deliveryKey());

        // Advance clock past retry delay (10 seconds) and process again
        setClock("2026-09-20T10:00:15Z");
        dueWorkService.processDueWork();

        ReminderResponse afterRetry = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, afterRetry.state(), "Should be DELIVERED after retry");
        assertEquals(2, afterRetry.attemptCount());
        assertEquals(2, afterRetry.attempts().size());
        assertEquals(AttemptStatus.SUCCESS, afterRetry.attempts().get(1).status());
    }

    // ========================================================================
    // Test 4: Retry exhaustion
    // ========================================================================
    @Test
    @Order(4)
    @DisplayName("Test 4: Retry exhaustion")
    void testRetryExhaustion() {
        setClock("2026-09-20T10:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("Will fail", "2026-09-20T15:30:00", "Asia/Kolkata"));

        // Configure all attempts to fail temporarily
        fakeDestination.configureBehavior(reminder.deliveryKey(),
                DeliveryResult.temporaryFailure("Always failing"));

        // Attempt 1 at 10:00
        setClock("2026-09-20T10:00:00Z");
        dueWorkService.processDueWork();

        ReminderResponse after1 = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.SCHEDULED, after1.state());

        // Attempt 2 at 10:00:15 (past 10s delay)
        setClock("2026-09-20T10:00:15Z");
        dueWorkService.processDueWork();

        ReminderResponse after2 = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.SCHEDULED, after2.state());

        // Attempt 3 at 10:01:00 (past 30s delay)
        setClock("2026-09-20T10:01:00Z");
        dueWorkService.processDueWork();

        // After 3 failed attempts (max), should be FAILED
        ReminderResponse afterExhaustion = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.FAILED, afterExhaustion.state(), "Should be FAILED after retry exhaustion");
        assertEquals(3, afterExhaustion.attemptCount());
        assertEquals(3, afterExhaustion.attempts().size());

        // No notification was delivered
        assertFalse(fakeDestination.wasDelivered(reminder.deliveryKey()));
    }

    // ========================================================================
    // Test 5: Duplicate execution / duplicate acknowledgement
    // ========================================================================
    @Test
    @Order(5)
    @DisplayName("Test 5: Duplicate execution / idempotency")
    void testDuplicateExecution() {
        setClock("2026-09-20T10:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("No dupes", "2026-09-20T15:30:00", "Asia/Kolkata"));

        // Process — should deliver
        setClock("2026-09-20T10:00:00Z");
        dueWorkService.processDueWork();

        ReminderResponse delivered = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, delivered.state());
        assertEquals(1, fakeDestination.getDeliveredCount());

        // Simulate duplicate execution: manually attempt delivery again for the same version
        ScheduledWork work = workRepository.findById(reminder.scheduledWorkId()).orElseThrow();
        // Force back to RUNNING to simulate a duplicate worker
        work.setState(WorkState.RUNNING);
        workRepository.save(work);

        deliveryService.attemptDelivery(work, reminder.version());

        // The fake destination should detect the duplicate
        assertEquals(1, fakeDestination.getDeliveredCount(),
                "Should still have only 1 logical notification despite duplicate execution");
    }

    // ========================================================================
    // Test 6: Editing before execution
    // ========================================================================
    @Test
    @Order(6)
    @DisplayName("Test 6: Editing before execution")
    void testEditBeforeExecution() {
        setClock("2026-09-20T09:00:00Z");
        ReminderResponse created = reminderService.createReminder(
                new CreateReminderRequest("Old content", "2026-09-20T16:00:00", "Asia/Kolkata"));

        assertEquals(1L, created.version());
        String oldDeliveryKey = created.deliveryKey();

        // Edit the reminder
        ReminderResponse updated = reminderService.updateReminder(
                created.scheduledWorkId(),
                new UpdateReminderRequest("New content", "2026-09-20T17:00:00", "Asia/Kolkata"));

        assertEquals(2L, updated.version());
        assertNotEquals(oldDeliveryKey, updated.deliveryKey());
        assertEquals("New content", updated.content());

        // New scheduled_at: 17:00 IST = 11:30 UTC
        // Process at 11:00 — not yet due
        setClock("2026-09-20T11:00:00Z");
        int processed = dueWorkService.processDueWork();
        assertEquals(0, processed);

        // Process at 11:30 — due
        setClock("2026-09-20T11:30:00Z");
        processed = dueWorkService.processDueWork();
        assertEquals(1, processed);

        ReminderResponse result = reminderService.getReminder(created.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, result.state());
        assertEquals("New content", result.content());

        // The new delivery key was used, not the old one
        assertTrue(fakeDestination.wasDelivered(updated.deliveryKey()));
        assertFalse(fakeDestination.wasDelivered(oldDeliveryKey));
    }

    // ========================================================================
    // Test 7: Cancellation before execution
    // ========================================================================
    @Test
    @Order(7)
    @DisplayName("Test 7: Cancellation before execution")
    void testCancellationBeforeExecution() {
        setClock("2026-09-20T09:00:00Z");
        ReminderResponse created = reminderService.createReminder(
                new CreateReminderRequest("Cancel me", "2026-09-20T16:00:00", "Asia/Kolkata"));

        // Cancel before it becomes due
        ReminderResponse cancelled = reminderService.cancelReminder(created.scheduledWorkId());
        assertEquals(WorkState.CANCELLED, cancelled.state());

        // Advance clock past scheduled time and try to process
        setClock("2026-09-20T11:00:00Z");
        int processed = dueWorkService.processDueWork();
        assertEquals(0, processed, "Cancelled work should not be processed");

        // Verify no notification
        assertFalse(fakeDestination.wasDelivered(created.deliveryKey()));
    }

    // ========================================================================
    // Test 8: Edit-vs-execution race
    // ========================================================================
    @Test
    @Order(8)
    @DisplayName("Test 8: Edit-vs-execution race")
    void testEditVsExecutionRace() {
        setClock("2026-09-20T09:00:00Z");
        ReminderResponse created = reminderService.createReminder(
                new CreateReminderRequest("Race content", "2026-09-20T15:30:00", "Asia/Kolkata"));
        // 15:30 IST = 10:00 UTC

        // Simulate: worker reads version 1 and claims it (set RUNNING via save)
        setClock("2026-09-20T10:00:00Z");
        ScheduledWork work = workRepository.findById(created.scheduledWorkId()).orElseThrow();
        long workerVersion = work.getVersion(); // version 1
        work.setState(WorkState.RUNNING);
        work.setUpdatedAt(clock());
        workRepository.save(work);

        // Meanwhile, user edits the reminder (version becomes 2)
        ReminderResponse edited = reminderService.updateReminder(
                created.scheduledWorkId(),
                new UpdateReminderRequest("Edited content", "2026-09-20T17:00:00", "Asia/Kolkata"));
        assertEquals(2L, edited.version());
        assertEquals(WorkState.SCHEDULED, edited.state()); // edit resets to SCHEDULED

        // Worker tries to deliver with stale version 1 — DeliveryService re-checks
        // Use the stale work object snapshot
        deliveryService.attemptDelivery(work, workerVersion);

        // The stale content should NOT have been logically delivered
        String oldDeliveryKey = created.scheduledWorkId() + ":1";
        assertFalse(fakeDestination.wasDelivered(oldDeliveryKey),
                "Stale version delivery key should not have produced a notification");

        // Now process the updated version
        setClock("2026-09-20T11:30:00Z"); // 17:00 IST = 11:30 UTC
        dueWorkService.processDueWork();

        ReminderResponse result = reminderService.getReminder(created.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, result.state());
        assertEquals("Edited content", result.content());
        assertTrue(fakeDestination.wasDelivered(edited.deliveryKey()));
    }

    // ========================================================================
    // Test 9: Cancellation-vs-execution race
    // ========================================================================
    @Test
    @Order(9)
    @DisplayName("Test 9: Cancellation-vs-execution race")
    void testCancellationVsExecutionRace() {
        setClock("2026-09-20T09:00:00Z");
        ReminderResponse created = reminderService.createReminder(
                new CreateReminderRequest("Race cancel", "2026-09-20T15:30:00", "Asia/Kolkata"));

        // Simulate: worker claims the work at 10:00 (set RUNNING via save)
        setClock("2026-09-20T10:00:00Z");
        ScheduledWork work = workRepository.findById(created.scheduledWorkId()).orElseThrow();
        long workerVersion = work.getVersion();
        work.setState(WorkState.RUNNING);
        work.setUpdatedAt(clock());
        workRepository.save(work);

        // Meanwhile, user cancels
        reminderService.cancelReminder(created.scheduledWorkId());

        // Worker attempts delivery — should detect cancellation
        deliveryService.attemptDelivery(work, workerVersion);

        // No notification should have been sent
        assertFalse(fakeDestination.wasDelivered(created.deliveryKey()),
                "Cancelled work should not produce a notification");

        // Final state should be CANCELLED
        ReminderResponse result = reminderService.getReminder(created.scheduledWorkId());
        assertEquals(WorkState.CANCELLED, result.state());
    }

    // ========================================================================
    // Test 10: Asia/Kolkata timezone conversion
    // ========================================================================
    @Test
    @Order(10)
    @DisplayName("Test 10: Asia/Kolkata timezone conversion")
    void testAsiaKolkataTimezoneConversion() {
        setClock("2026-09-20T09:00:00Z");

        // Asia/Kolkata = UTC+5:30
        // 18:00 IST should be 12:30 UTC
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("India reminder", "2026-09-20T18:00:00", "Asia/Kolkata"));

        Instant expectedInstant = Instant.parse("2026-09-20T12:30:00Z");
        assertEquals(expectedInstant, reminder.scheduledAt(),
                "18:00 Asia/Kolkata should convert to 12:30 UTC");
        assertEquals("Asia/Kolkata", reminder.timeZone());
    }

    // ========================================================================
    // Test 11: America/New_York timezone conversion
    // ========================================================================
    @Test
    @Order(11)
    @DisplayName("Test 11: America/New_York timezone conversion")
    void testAmericaNewYorkTimezoneConversion() {
        setClock("2026-09-20T09:00:00Z");

        // September 20 is in EDT (UTC-4)
        // 14:00 EDT should be 18:00 UTC
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("NYC reminder", "2026-09-20T14:00:00", "America/New_York"));

        Instant expectedInstant = Instant.parse("2026-09-20T18:00:00Z");
        assertEquals(expectedInstant, reminder.scheduledAt(),
                "14:00 America/New_York (EDT) should convert to 18:00 UTC");
        assertEquals("America/New_York", reminder.timeZone());
    }

    // ========================================================================
    // Test 12: Daylight-saving boundary
    // ========================================================================
    @Test
    @Order(12)
    @DisplayName("Test 12: DST spring-forward boundary (nonexistent time)")
    void testDstSpringForwardBoundary() {
        setClock("2026-03-08T05:00:00Z");

        // America/New_York springs forward on March 8, 2026: 2:00 AM → 3:00 AM
        // 2:30 AM does NOT exist during spring-forward
        // Java's ZonedDateTime.of() shifts forward by the gap size:
        // 2:30 AM + 1h gap = 3:30 AM EDT
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("DST test",
                        "2026-03-08T02:30:00", "America/New_York"));

        // 3:30 AM EDT = UTC-4 = 07:30 UTC
        Instant expectedInstant = Instant.parse("2026-03-08T07:30:00Z");
        assertEquals(expectedInstant, reminder.scheduledAt(),
                "Nonexistent 2:30 AM during spring-forward should shift to 3:30 AM EDT (07:30 UTC)");
    }

    @Test
    @Order(13)
    @DisplayName("Test 12b: DST fall-back boundary (ambiguous time)")
    void testDstFallBackBoundary() {
        setClock("2026-11-01T04:00:00Z");

        // America/New_York falls back on November 1, 2026: 2:00 AM → 1:00 AM
        // 1:30 AM occurs TWICE
        // Policy: choose the earlier offset (EDT = UTC-4)
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("DST fallback test",
                        "2026-11-01T01:30:00", "America/New_York"));

        // 1:30 AM EDT (earlier offset) = UTC-4 = 05:30 UTC
        Instant expectedInstant = Instant.parse("2026-11-01T05:30:00Z");
        assertEquals(expectedInstant, reminder.scheduledAt(),
                "Ambiguous 1:30 AM during fall-back should use earlier offset (EDT, UTC-4)");
    }

    // ========================================================================
    // Test: Recovery of RUNNING work on restart
    // ========================================================================
    @Test
    @Order(14)
    @DisplayName("Test: RUNNING work recovery on restart")
    void testRunningWorkRecovery() {
        setClock("2026-09-20T09:00:00Z");
        ReminderResponse created = reminderService.createReminder(
                new CreateReminderRequest("Stuck item", "2026-09-20T15:30:00", "Asia/Kolkata"));

        // Simulate: work was claimed but process crashed before delivery (set RUNNING via save)
        setClock("2026-09-20T10:00:00Z");
        ScheduledWork work = workRepository.findById(created.scheduledWorkId()).orElseThrow();
        work.setState(WorkState.RUNNING);
        work.setUpdatedAt(clock());
        workRepository.save(work);

        ReminderResponse beforeRecovery = reminderService.getReminder(created.scheduledWorkId());
        assertEquals(WorkState.RUNNING, beforeRecovery.state());

        // Simulate restart: recover stuck work
        int recovered = dueWorkService.recoverStuckWork();
        assertEquals(1, recovered);

        ReminderResponse afterRecovery = reminderService.getReminder(created.scheduledWorkId());
        assertEquals(WorkState.SCHEDULED, afterRecovery.state(), "RUNNING work should be recovered to SCHEDULED");

        // Now process it
        dueWorkService.processDueWork();
        ReminderResponse result = reminderService.getReminder(created.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, result.state());
    }

    // ========================================================================
    // Test: Permanent failure
    // ========================================================================
    @Test
    @Order(15)
    @DisplayName("Test: Permanent failure goes directly to FAILED")
    void testPermanentFailure() {
        setClock("2026-09-20T10:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("Perm fail", "2026-09-20T15:30:00", "Asia/Kolkata"));

        fakeDestination.configureBehavior(reminder.deliveryKey(),
                DeliveryResult.permanentFailure("Invalid recipient"));

        dueWorkService.processDueWork();

        ReminderResponse result = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.FAILED, result.state(), "Permanent failure should go directly to FAILED");
        assertEquals(1, result.attemptCount());
        assertEquals(AttemptStatus.PERMANENT_FAILURE, result.attempts().get(0).status());
    }

    // ========================================================================
    // Test: Validation errors
    // ========================================================================
    @Test
    @Order(16)
    @DisplayName("Test: Invalid timezone validation")
    void testInvalidTimezoneValidation() {
        setClock("2026-09-20T10:00:00Z");
        assertThrows(com.example.reminders.exception.InvalidTimeZoneException.class, () ->
                reminderService.createReminder(
                        new CreateReminderRequest("Bad zone", "2026-09-20T18:00:00", "Invalid/Zone")));
    }

    @Test
    @Order(17)
    @DisplayName("Test: Cannot edit delivered reminder")
    void testCannotEditDeliveredReminder() {
        setClock("2026-09-20T10:00:00Z");
        ReminderResponse reminder = reminderService.createReminder(
                new CreateReminderRequest("Done", "2026-09-20T15:30:00", "Asia/Kolkata"));

        dueWorkService.processDueWork();
        ReminderResponse delivered = reminderService.getReminder(reminder.scheduledWorkId());
        assertEquals(WorkState.DELIVERED, delivered.state());

        assertThrows(com.example.reminders.exception.InvalidReminderStateException.class, () ->
                reminderService.updateReminder(reminder.scheduledWorkId(),
                        new UpdateReminderRequest("New", "2026-09-21T10:00:00", "Asia/Kolkata")));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void setClock(String instant) {
        CLOCK_REF.set(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private Instant clock() {
        return CLOCK_REF.get().instant();
    }
}
