package com.rajasekhar.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCompletedEvent(
    UUID paymentId,
    String sourceAccountId,
    String destinationAccountId,
    BigDecimal amount,
    String currency,
    Instant occurredAt
) {}
