package com.example.reminders.service;

import com.example.reminders.entity.ScheduledWork;
import com.example.reminders.model.WorkState;
import com.example.reminders.repository.ScheduledWorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Discovers due work from the database and delegates processing to DeliveryService.
 *
 * <p>This service is called by:
 * <ul>
 *   <li>The background scheduler (SchedulerConfig)</li>
 *   <li>The manual trigger endpoint (POST /api/v1/reminders/process-due)</li>
 * </ul>
 *
 * <p>Discovery logic: finds all work where state=SCHEDULED AND scheduledAt <= now
 * AND nextAttemptAt <= now. This naturally discovers overdue work after restart
 * because the comparison is against the current clock time.
 *
 * <p>Claiming: uses a conditional update (state=SCHEDULED AND version=expected → RUNNING)
 * to prevent two workers from both claiming the same item.
 *
 * <p>Restart recovery: on startup, RUNNING work from a crashed process is reset
 * to SCHEDULED so it can be rediscovered.
 */
@Service
public class DueWorkService {

    private static final Logger log = LoggerFactory.getLogger(DueWorkService.class);

    private final ScheduledWorkRepository workRepository;
    private final DeliveryService deliveryService;
    private final Clock clock;

    public DueWorkService(ScheduledWorkRepository workRepository,
                          DeliveryService deliveryService,
                          Clock clock) {
        this.workRepository = workRepository;
        this.deliveryService = deliveryService;
        this.clock = clock;
    }

    /**
     * Discovers and processes all due work items.
     *
     * @return the number of items that were claimed and processed
     */
    @Transactional
    public int processDueWork() {
        Instant now = clock.instant();
        List<ScheduledWork> dueWork = workRepository.findDueWork(now);

        if (dueWork.isEmpty()) {
            log.debug("No due work found at {}", now);
            return 0;
        }

        log.info("Found {} due work items at {}", dueWork.size(), now);
        int processed = 0;

        for (ScheduledWork work : dueWork) {
            long versionAtDiscovery = work.getVersion();

            // Attempt to claim: conditional update ensures only one worker wins
            int claimed = workRepository.claimWork(
                    work.getScheduledWorkId(), versionAtDiscovery, now);

            if (claimed == 0) {
                log.info("Failed to claim work {} (already claimed or modified)",
                        work.getScheduledWorkId());
                continue;
            }

            log.info("Claimed work {} (version={})", work.getScheduledWorkId(), versionAtDiscovery);

            // Re-read to get the updated entity after claim
            ScheduledWork claimed_work = workRepository.findById(work.getScheduledWorkId())
                    .orElse(null);
            if (claimed_work == null) {
                continue;
            }

            // Delegate delivery to the DeliveryService
            try {
                deliveryService.attemptDelivery(claimed_work, versionAtDiscovery);
                processed++;
            } catch (Exception e) {
                log.error("Error processing work {}: {}", work.getScheduledWorkId(), e.getMessage(), e);
                // Ensure work doesn't get stuck in RUNNING
                recoverStuckWork(work.getScheduledWorkId());
            }
        }

        return processed;
    }

    /**
     * Restart recovery: resets RUNNING work from a previous crashed process
     * back to SCHEDULED so it can be rediscovered.
     *
     * <p>Policy: Any work in RUNNING state that hasn't been durably marked
     * DELIVERED is assumed to be from a crashed worker and is returned to SCHEDULED.
     */
    @Transactional
    public int recoverStuckWork() {
        List<ScheduledWork> runningWork = workRepository.findByState(WorkState.RUNNING);

        if (runningWork.isEmpty()) {
            return 0;
        }

        log.info("Recovering {} stuck RUNNING work items", runningWork.size());
        Instant now = clock.instant();

        for (ScheduledWork work : runningWork) {
            work.setState(WorkState.SCHEDULED);
            work.setNextAttemptAt(now); // Make immediately eligible
            work.setUpdatedAt(now);
            workRepository.save(work);
            log.info("Recovered work {} from RUNNING to SCHEDULED", work.getScheduledWorkId());
        }

        return runningWork.size();
    }

    /**
     * Recovers a specific work item that got stuck in RUNNING state due to an error.
     */
    private void recoverStuckWork(java.util.UUID workId) {
        workRepository.findById(workId).ifPresent(work -> {
            if (work.getState() == WorkState.RUNNING) {
                work.setState(WorkState.SCHEDULED);
                work.setNextAttemptAt(clock.instant());
                work.setUpdatedAt(clock.instant());
                workRepository.save(work);
                log.info("Recovered stuck work {} back to SCHEDULED", workId);
            }
        });
    }
}
