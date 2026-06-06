package com.rajasekhar.payment.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Inbound payment request.
 *
 * idempotencyKey is mandatory — clients MUST generate a UUID per payment attempt.
 * The API will return the same response for duplicate keys without re-processing.
 */
public record PaymentRequest(

    @NotBlank(message = "Idempotency key is required")
    @Size(min = 8, max = 64, message = "Idempotency key must be 8-64 characters")
    String idempotencyKey,

    @NotBlank(message = "Source account ID is required")
    String sourceAccountId,

    @NotBlank(message = "Destination account ID is required")
    String destinationAccountId,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @DecimalMax(value = "10000000.00", message = "Amount exceeds maximum transfer limit")
    @Digits(integer = 16, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be ISO 4217 3-letter code")
    String currency
) {}
