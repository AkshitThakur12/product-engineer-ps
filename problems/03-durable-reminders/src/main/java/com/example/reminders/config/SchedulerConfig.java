package com.example.reminders.config;

import com.example.reminders.service.DueWorkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background scheduler that periodically discovers and processes due work.
 *
 * <p>The scheduler is only a trigger. All business logic, retry policy,
 * timezone handling, and state transitions are in the service layer.
 *
 * <p>On application startup, performs restart recovery to reset any
 * RUNNING work from a previously crashed process back to SCHEDULED.
 *
 * <p>Can be disabled via app.scheduler.enabled=false (e.g. in tests).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    private final DueWorkService dueWorkService;

    public SchedulerConfig(DueWorkService dueWorkService) {
        this.dueWorkService = dueWorkService;
    }

    /**
     * On application startup, recover any work stuck in RUNNING state
     * from a previous process crash.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Application started — performing restart recovery...");
        int recovered = dueWorkService.recoverStuckWork();
        if (recovered > 0) {
            log.info("Recovered {} stuck work items on startup", recovered);
        } else {
            log.info("No stuck work items found on startup");
        }
    }

    /**
     * Periodic background trigger for due-work processing.
     * Uses the same DueWorkService as the manual REST endpoint.
     */
    @Scheduled(fixedDelayString = "${app.scheduler.fixed-delay-ms:5000}")
    public void pollDueWork() {
        try {
            int processed = dueWorkService.processDueWork();
            if (processed > 0) {
                log.info("Scheduler processed {} due work items", processed);
            }
        } catch (Exception e) {
            log.error("Error during scheduled due-work processing: {}", e.getMessage(), e);
        }
    }
}
