package com.example.reminders.model;

/**
 * Lifecycle states for a scheduled work item.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>SCHEDULED → RUNNING  (claimed for execution)</li>
 *   <li>SCHEDULED → CANCELLED (user cancellation)</li>
 *   <li>RUNNING → DELIVERED   (successful delivery)</li>
 *   <li>RUNNING → SCHEDULED   (retry after temporary failure)</li>
 *   <li>RUNNING → FAILED      (retries exhausted or permanent failure)</li>
 *   <li>RUNNING → CANCELLED   (cancellation wins before delivery commits)</li>
 * </ul>
 */
public enum WorkState {
    SCHEDULED,
    RUNNING,
    DELIVERED,
    CANCELLED,
    FAILED;

    /**
     * Returns true if transitioning from this state to the target state is valid.
     */
    public boolean canTransitionTo(WorkState target) {
        return switch (this) {
            case SCHEDULED -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == DELIVERED || target == SCHEDULED
                    || target == FAILED || target == CANCELLED;
            case DELIVERED, CANCELLED, FAILED -> false;
        };
    }

    /**
     * Returns true if this state is a terminal state (no further transitions allowed).
     */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == FAILED;
    }
}
