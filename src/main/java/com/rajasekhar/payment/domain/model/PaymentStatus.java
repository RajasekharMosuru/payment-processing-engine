package com.rajasekhar.payment.domain.model;

import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle state machine.
 *
 * Allowed transitions:
 *   INITIATED  -> PROCESSING
 *   PROCESSING -> COMPLETED | FAILED
 *   COMPLETED  -> (terminal — no further transitions)
 *   FAILED     -> PROCESSING  (retry path — guarded by retryCount < MAX_RETRIES)
 */
public enum PaymentStatus {
    INITIATED,
    PROCESSING,
    COMPLETED,
    FAILED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
        INITIATED,  Set.of(PROCESSING),
        PROCESSING, Set.of(COMPLETED, FAILED),
        COMPLETED,  Set.of(),
        FAILED,     Set.of(PROCESSING)
    );

    public boolean canTransitionTo(PaymentStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
