package com.example.reminders.service;

import com.example.reminders.dto.CreateReminderRequest;
import com.example.reminders.dto.ReminderResponse;
import com.example.reminders.dto.UpdateReminderRequest;
import com.example.reminders.entity.DeliveryAttempt;
import com.example.reminders.entity.ScheduledWork;
import com.example.reminders.exception.InvalidReminderStateException;
import com.example.reminders.exception.InvalidTimeZoneException;
import com.example.reminders.exception.ReminderNotFoundException;
import com.example.reminders.model.WorkState;
import com.example.reminders.repository.DeliveryAttemptRepository;
import com.example.reminders.repository.ScheduledWorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for creating, reading, updating, and cancelling reminders.
 *
 * <p>Time-zone policy:
 * <ul>
 *   <li>Normal local time: converted normally.</li>
 *   <li>Ambiguous local time (DST fallback): the earlier valid offset is chosen.</li>
 *   <li>Nonexistent local time (DST spring-forward): shifted forward to next valid local time.</li>
 * </ul>
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ScheduledWorkRepository workRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final Clock clock;

    public ReminderService(ScheduledWorkRepository workRepository,
                           DeliveryAttemptRepository attemptRepository,
                           Clock clock) {
        this.workRepository = workRepository;
        this.attemptRepository = attemptRepository;
        this.clock = clock;
    }

    /**
     * Creates a new scheduled reminder.
     */
    @Transactional
    public ReminderResponse createReminder(CreateReminderRequest request) {
        ZoneId zoneId = validateAndParseZone(request.timeZone());
        LocalDateTime localDateTime = LocalDateTime.parse(request.localDateTime());
        Instant scheduledAt = convertToInstant(localDateTime, zoneId);

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        ScheduledWork work = new ScheduledWork(id, request.content(), scheduledAt,
                request.timeZone(), now);

        work = workRepository.save(work);
        log.info("Created reminder {} scheduled at {} (zone: {})", id, scheduledAt, request.timeZone());

        return ReminderResponse.from(work, List.of());
    }

    /**
     * Returns a reminder with its delivery attempt history.
     */
    @Transactional(readOnly = true)
    public ReminderResponse getReminder(UUID scheduledWorkId) {
        ScheduledWork work = findWorkOrThrow(scheduledWorkId);
        List<DeliveryAttempt> attempts = attemptRepository
                .findByScheduledWorkIdOrderByAttemptNumberAsc(scheduledWorkId);
        return ReminderResponse.from(work, attempts);
    }

    /**
     * Updates a reminder's content and/or scheduled time.
     * Only allowed before successful delivery (state must be SCHEDULED or RUNNING).
     *
     * <p>Increments the version and regenerates the delivery key so that
     * any worker holding the old version will detect the mismatch before delivering.
     */
    @Transactional
    public ReminderResponse updateReminder(UUID scheduledWorkId, UpdateReminderRequest request) {
        ScheduledWork work = findWorkOrThrow(scheduledWorkId);

        if (work.getState().isTerminal()) {
            throw new InvalidReminderStateException(
                    "Cannot edit reminder in state: " + work.getState());
        }

        ZoneId zoneId = validateAndParseZone(request.timeZone());
        LocalDateTime localDateTime = LocalDateTime.parse(request.localDateTime());
        Instant scheduledAt = convertToInstant(localDateTime, zoneId);

        work.setContent(request.content());
        work.setScheduledAt(scheduledAt);
        work.setTimeZone(request.timeZone());
        work.setNextAttemptAt(scheduledAt);
        work.setAttemptCount(0);

        // If currently RUNNING, move back to SCHEDULED so the new version is picked up
        if (work.getState() == WorkState.RUNNING) {
            work.setState(WorkState.SCHEDULED);
        }

        // Increment version and regenerate delivery key
        work.incrementVersion();
        work.setUpdatedAt(clock.instant());

        work = workRepository.save(work);
        log.info("Updated reminder {} to version {} with new delivery key {}",
                scheduledWorkId, work.getVersion(), work.getDeliveryKey());

        List<DeliveryAttempt> attempts = attemptRepository
                .findByScheduledWorkIdOrderByAttemptNumberAsc(scheduledWorkId);
        return ReminderResponse.from(work, attempts);
    }

    /**
     * Cancels a scheduled reminder.
     * Only active (SCHEDULED or RUNNING) work can be cancelled.
     */
    @Transactional
    public ReminderResponse cancelReminder(UUID scheduledWorkId) {
        ScheduledWork work = findWorkOrThrow(scheduledWorkId);

        if (!work.getState().canTransitionTo(WorkState.CANCELLED)) {
            throw new InvalidReminderStateException(
                    "Cannot cancel reminder in state: " + work.getState());
        }

        work.setState(WorkState.CANCELLED);
        work.setUpdatedAt(clock.instant());
        work = workRepository.save(work);
        log.info("Cancelled reminder {}", scheduledWorkId);

        List<DeliveryAttempt> attempts = attemptRepository
                .findByScheduledWorkIdOrderByAttemptNumberAsc(scheduledWorkId);
        return ReminderResponse.from(work, attempts);
    }

    /**
     * Converts a local date-time in the given timezone to a UTC Instant.
     *
     * <p>DST policy:
     * <ul>
     *   <li>Ambiguous (fallback): chooses the earlier offset</li>
     *   <li>Nonexistent (spring-forward): shifts forward to next valid time</li>
     * </ul>
     */
    public Instant convertToInstant(LocalDateTime localDateTime, ZoneId zoneId) {
        // withEarlierOffsetAtOverlap(): for ambiguous times, choose the earlier offset
        // This also handles nonexistent times by default:
        //   ZonedDateTime.of() shifts forward for gaps (spring-forward)
        ZonedDateTime zdt = localDateTime.atZone(zoneId).withEarlierOffsetAtOverlap();
        return zdt.toInstant();
    }

    private ScheduledWork findWorkOrThrow(UUID scheduledWorkId) {
        return workRepository.findById(scheduledWorkId)
                .orElseThrow(() -> new ReminderNotFoundException(scheduledWorkId));
    }

    private ZoneId validateAndParseZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new InvalidTimeZoneException(timeZone);
        }
    }
}
