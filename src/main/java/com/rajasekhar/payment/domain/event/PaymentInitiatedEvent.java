package com.rajasekhar.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published to Kafka when a payment moves to PROCESSING.
 *
 * Downstream consumers (notification service, ledger service, audit service)
 * subscribe to the payments.initiated topic to react independently —
 * this decouples the core payment flow from cross-cutting concerns.
 *
 * Note: kept as a plain record (no Jackson annotations) so serialisation
 * strategy can be swapped between JSON and Avro without touching domain code.
 */
public record PaymentInitiatedEvent(
    UUID paymentId,
    String idempotencyKey,
    String sourceAccountId,
    String destinationAccountId,
    BigDecimal amount,
    String currency,
    Instant occurredAt
) {
    public static PaymentInitiatedEvent of(
            UUID paymentId,
            String idempotencyKey,
            String sourceAccountId,
            String destinationAccountId,
            BigDecimal amount,
            String currency) {
        return new PaymentInitiatedEvent(
            paymentId, idempotencyKey, sourceAccountId,
            destinationAccountId, amount, currency, Instant.now());
    }
}
