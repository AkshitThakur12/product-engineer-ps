package com.example.reminders.service;

import com.example.reminders.entity.DeliveryAttempt;
import com.example.reminders.entity.ScheduledWork;
import com.example.reminders.model.AttemptStatus;
import com.example.reminders.model.DeliveryResult;
import com.example.reminders.model.WorkState;
import com.example.reminders.notification.NotificationDestination;
import com.example.reminders.repository.DeliveryAttemptRepository;
import com.example.reminders.repository.ScheduledWorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Handles the actual delivery of notifications and manages retry logic.
 *
 * <p>Retry policy:
 * <ul>
 *   <li>Maximum attempts: configurable (default 3)</li>
 *   <li>Delay between attempts: configurable (default: 0s, 10s, 30s)</li>
 *   <li>Only temporary failures are retried</li>
 *   <li>Permanent failures immediately move to FAILED state</li>
 * </ul>
 *
 * <p>Before committing a successful delivery, the service re-checks that:
 * <ul>
 *   <li>The work is still in RUNNING state</li>
 *   <li>The version has not changed (no edit/cancellation race)</li>
 * </ul>
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final ScheduledWorkRepository workRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final NotificationDestination notificationDestination;
    private final Clock clock;
    private final int maxAttempts;
    private final List<Long> delaySeconds;

    public DeliveryService(ScheduledWorkRepository workRepository,
                           DeliveryAttemptRepository attemptRepository,
                           NotificationDestination notificationDestination,
                           Clock clock,
                           @Value("${app.retry.max-attempts:3}") int maxAttempts,
                           @Value("${app.retry.delays-seconds:0,10,30}") String delaysConfig) {
        this.workRepository = workRepository;
        this.attemptRepository = attemptRepository;
        this.notificationDestination = notificationDestination;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.delaySeconds = Arrays.stream(delaysConfig.split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .toList();
    }

    /**
     * Attempts delivery for a claimed (RUNNING) work item.
     *
     * <p>Flow:
     * <ol>
     *   <li>Call the notification destination with the delivery key</li>
     *   <li>Record the attempt</li>
     *   <li>On SUCCESS: re-verify state/version, then mark DELIVERED</li>
     *   <li>On TEMPORARY_FAILURE: schedule retry or mark FAILED if exhausted</li>
     *   <li>On PERMANENT_FAILURE: mark FAILED immediately</li>
     *   <li>On DUPLICATE: mark DELIVERED (the logical notification already happened)</li>
     * </ol>
     */
    @Transactional
    public void attemptDelivery(ScheduledWork work, long claimedVersion) {
        // Pre-check against database to detect races before delivering
        ScheduledWork current = workRepository.findById(work.getScheduledWorkId()).orElse(null);
        if (current == null) {
            log.warn("Work {} no longer exists before delivery attempt", work.getScheduledWorkId());
            return;
        }

        if (!current.getVersion().equals(claimedVersion)) {
            log.info("Version mismatch for work {} (claimed={}, current={}). Edit occurred — aborting delivery.",
                    work.getScheduledWorkId(), claimedVersion, current.getVersion());
            return;
        }

        if (current.getState() == WorkState.CANCELLED) {
            log.info("Work {} is CANCELLED — aborting delivery.", work.getScheduledWorkId());
            return;
        }

        if (current.getState() != WorkState.RUNNING) {
            log.info("Work {} is no longer RUNNING (state={}). Aborting delivery.",
                    work.getScheduledWorkId(), current.getState());
            return;
        }

        String deliveryKey = current.getDeliveryKey();
        int attemptNumber = current.getAttemptCount() + 1;
        Instant now = clock.instant();

        log.info("Attempting delivery for work {} (version={}, attempt={}, key={})",
                current.getScheduledWorkId(), claimedVersion, attemptNumber, deliveryKey);

        // Perform delivery outside the critical state-transition logic
        DeliveryResult result;
        try {
            result = notificationDestination.deliver(deliveryKey, current.getContent());
        } catch (Exception e) {
            log.error("Unexpected error during delivery for key {}: {}", deliveryKey, e.getMessage());
            result = DeliveryResult.temporaryFailure("Unexpected error: " + e.getMessage());
        }

        // Record the attempt
        DeliveryAttempt attempt = new DeliveryAttempt(
                current.getScheduledWorkId(),
                attemptNumber,
                deliveryKey,
                result.status(),
                result.errorMessage(),
                now
        );
        attemptRepository.save(attempt);

        // Re-read the work to check for races during delivery call (edit or cancellation)
        current = workRepository.findById(work.getScheduledWorkId()).orElse(null);
        if (current == null) {
            log.warn("Work {} no longer exists after delivery attempt", work.getScheduledWorkId());
            return;
        }

        if (!current.getVersion().equals(claimedVersion)) {
            log.info("Version mismatch for work {} (claimed={}, current={}). " +
                     "Edit occurred during execution — abandoning state transition.",
                    work.getScheduledWorkId(), claimedVersion, current.getVersion());
            return;
        }

        if (current.getState() != WorkState.RUNNING) {
            log.info("Work {} is no longer RUNNING (state={}). Abandoning state transition.",
                    work.getScheduledWorkId(), current.getState());
            return;
        }

        // Handle the result
        switch (result.status()) {
            case SUCCESS, DUPLICATE -> {
                current.setState(WorkState.DELIVERED);
                current.setAttemptCount(attemptNumber);
                current.setUpdatedAt(now);
                workRepository.save(current);
                log.info("Work {} marked as DELIVERED (attempt={})", work.getScheduledWorkId(), attemptNumber);
            }
            case TEMPORARY_FAILURE -> {
                handleTemporaryFailure(current, attemptNumber, now);
            }
            case PERMANENT_FAILURE -> {
                current.setState(WorkState.FAILED);
                current.setAttemptCount(attemptNumber);
                current.setUpdatedAt(now);
                workRepository.save(current);
                log.info("Work {} marked as FAILED due to permanent failure", work.getScheduledWorkId());
            }
        }
    }

    /**
     * Handles a temporary failure: schedules a retry if attempts remain,
     * otherwise marks the work as FAILED.
     */
    private void handleTemporaryFailure(ScheduledWork work, int attemptNumber, Instant now) {
        work.setAttemptCount(attemptNumber);

        if (attemptNumber >= maxAttempts) {
            // Retry exhaustion
            work.setState(WorkState.FAILED);
            work.setUpdatedAt(now);
            workRepository.save(work);
            log.info("Work {} marked as FAILED after {} attempts (retry exhaustion)",
                    work.getScheduledWorkId(), attemptNumber);
        } else {
            // Schedule retry: move back to SCHEDULED with a delay
            long delaySecs = getRetryDelay(attemptNumber);
            Instant nextAttempt = now.plus(Duration.ofSeconds(delaySecs));

            work.setState(WorkState.SCHEDULED);
            work.setNextAttemptAt(nextAttempt);
            work.setUpdatedAt(now);
            workRepository.save(work);
            log.info("Work {} scheduled for retry at {} (attempt {} of {}, delay={}s)",
                    work.getScheduledWorkId(), nextAttempt, attemptNumber + 1, maxAttempts, delaySecs);
        }
    }

    /**
     * Returns the retry delay for the given attempt number (0-indexed delays list).
     * If the attempt number exceeds the delays list, uses the last configured delay.
     */
    private long getRetryDelay(int attemptNumber) {
        int index = Math.min(attemptNumber, delaySeconds.size() - 1);
        return delaySeconds.get(index);
    }
}
