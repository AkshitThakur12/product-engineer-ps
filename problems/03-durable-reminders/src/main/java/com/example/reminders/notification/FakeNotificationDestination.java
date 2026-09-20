package com.example.reminders.notification;

import com.example.reminders.model.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fake/local notification destination for testing and demo purposes.
 *
 * <p>Features:
 * <ul>
 *   <li>Tracks delivered delivery keys for duplicate detection</li>
 *   <li>Supports configurable per-key behavior (success, temp failure, perm failure)</li>
 *   <li>Exposes delivered keys and counts for test verification</li>
 * </ul>
 *
 * <p>Idempotency: If a delivery key has already been successfully delivered,
 * a duplicate call returns DUPLICATE without sending a second logical notification.
 */
@Component
public class FakeNotificationDestination implements NotificationDestination {

    private static final Logger log = LoggerFactory.getLogger(FakeNotificationDestination.class);

    /** Set of delivery keys that have been successfully delivered. */
    private final Set<String> deliveredKeys = ConcurrentHashMap.newKeySet();

    /** Configurable behavior overrides per delivery key. */
    private final Map<String, DeliveryResult> configuredBehaviors = new ConcurrentHashMap<>();

    /** Record of all delivery key → content pairs that were logically delivered. */
    private final Map<String, String> deliveredContent = new ConcurrentHashMap<>();

    @Override
    public DeliveryResult deliver(String deliveryKey, String content) {
        // Idempotency check: if this key was already successfully delivered, return DUPLICATE
        if (deliveredKeys.contains(deliveryKey)) {
            log.info("Duplicate delivery detected for key: {}", deliveryKey);
            return DeliveryResult.duplicate();
        }

        // Check for configured behavior override (for testing failure scenarios)
        DeliveryResult configured = configuredBehaviors.get(deliveryKey);
        if (configured != null) {
            log.info("Configured behavior for key {}: {}", deliveryKey, configured.status());
            // Only remove the override and record success if it's a SUCCESS result
            if (configured.status() == com.example.reminders.model.AttemptStatus.SUCCESS) {
                configuredBehaviors.remove(deliveryKey);
                deliveredKeys.add(deliveryKey);
                deliveredContent.put(deliveryKey, content);
            }
            return configured;
        }

        // Default: successful delivery
        deliveredKeys.add(deliveryKey);
        deliveredContent.put(deliveryKey, content);
        log.info("Successfully delivered notification for key: {} with content: {}", deliveryKey, content);
        return DeliveryResult.success();
    }

    // ---- Configuration methods for tests ----

    /**
     * Configures the behavior for a specific delivery key.
     * The configured result will be returned on the next delivery attempt for that key.
     */
    public void configureBehavior(String deliveryKey, DeliveryResult result) {
        configuredBehaviors.put(deliveryKey, result);
    }

    /**
     * Removes the configured behavior override for a delivery key,
     * allowing subsequent deliveries to succeed by default.
     */
    public void clearBehavior(String deliveryKey) {
        configuredBehaviors.remove(deliveryKey);
    }

    // ---- Verification methods for tests ----

    /** Returns an unmodifiable view of all successfully delivered keys. */
    public Set<String> getDeliveredKeys() {
        return Collections.unmodifiableSet(deliveredKeys);
    }

    /** Returns the content delivered for a specific key, or null if not delivered. */
    public String getDeliveredContent(String deliveryKey) {
        return deliveredContent.get(deliveryKey);
    }

    /** Returns the number of unique logical notifications delivered. */
    public int getDeliveredCount() {
        return deliveredKeys.size();
    }

    /** Returns true if the given delivery key was successfully delivered. */
    public boolean wasDelivered(String deliveryKey) {
        return deliveredKeys.contains(deliveryKey);
    }

    /** Resets all state. Use between tests. */
    public void reset() {
        deliveredKeys.clear();
        configuredBehaviors.clear();
        deliveredContent.clear();
    }
}
