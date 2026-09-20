package com.example.reminders.notification;

import com.example.reminders.model.DeliveryResult;

/**
 * Abstraction for the notification delivery boundary.
 *
 * <p>The delivery key serves as the idempotency key. Implementations must
 * ensure that the same delivery key never results in two logical successful
 * notifications.
 */
public interface NotificationDestination {

    /**
     * Delivers a notification.
     *
     * @param deliveryKey unique key for this delivery occurrence (idempotency key)
     * @param content     the notification content
     * @return the delivery result indicating success, temporary failure, or permanent failure
     */
    DeliveryResult deliver(String deliveryKey, String content);
}
