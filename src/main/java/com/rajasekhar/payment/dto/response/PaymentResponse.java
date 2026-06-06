package com.rajasekhar.payment.dto.response;

import com.rajasekhar.payment.domain.model.Payment;
import com.rajasekhar.payment.domain.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    String idempotencyKey,
    String sourceAccountId,
    String destinationAccountId,
    BigDecimal amount,
    String currency,
    PaymentStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getIdempotencyKey(),
            payment.getSourceAccountId(),
            payment.getDestinationAccountId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getStatus(),
            payment.getCreatedAt(),
            payment.getUpdatedAt()
        );
    }
}
