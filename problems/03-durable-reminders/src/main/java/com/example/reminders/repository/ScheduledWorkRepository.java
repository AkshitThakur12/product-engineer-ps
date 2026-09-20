package com.example.reminders.repository;

import com.example.reminders.entity.ScheduledWork;
import com.example.reminders.model.WorkState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduledWorkRepository extends JpaRepository<ScheduledWork, UUID> {

    /**
     * Finds all work that is due for execution:
     * state = SCHEDULED AND scheduled_at <= now AND next_attempt_at <= now.
     */
    @Query("SELECT sw FROM ScheduledWork sw " +
           "WHERE sw.state = 'SCHEDULED' " +
           "AND sw.scheduledAt <= :now " +
           "AND sw.nextAttemptAt <= :now")
    List<ScheduledWork> findDueWork(@Param("now") Instant now);

    /**
     * Atomically claims a work item by updating state to RUNNING,
     * only if the current state and version match expected values.
     *
     * @return the number of rows updated (1 if claimed, 0 if already claimed/modified)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ScheduledWork sw " +
           "SET sw.state = 'RUNNING', sw.updatedAt = :now " +
           "WHERE sw.scheduledWorkId = :id " +
           "AND sw.state = 'SCHEDULED' " +
           "AND sw.version = :expectedVersion")
    int claimWork(@Param("id") UUID id,
                  @Param("expectedVersion") Long expectedVersion,
                  @Param("now") Instant now);

    /**
     * Finds all work items in the RUNNING state.
     * Used for restart recovery: RUNNING work from a previous process
     * that was never durably marked DELIVERED.
     */
    List<ScheduledWork> findByState(WorkState state);
}
