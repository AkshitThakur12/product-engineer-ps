package com.example.reminders.repository;

import com.example.reminders.entity.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    /**
     * Returns all delivery attempts for a given scheduled work item,
     * ordered by attempt number ascending.
     */
    List<DeliveryAttempt> findByScheduledWorkIdOrderByAttemptNumberAsc(UUID scheduledWorkId);

    /**
     * Returns all delivery attempts with the given delivery key.
     */
    List<DeliveryAttempt> findByDeliveryKey(String deliveryKey);
}
