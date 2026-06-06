package com.rajasekhar.payment.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Core payment aggregate root.
 *
 * Design decisions:
 *  - idempotencyKey: client-supplied UUID prevents duplicate processing on retries.
 *  - status drives the saga state machine: INITIATED -> PROCESSING -> COMPLETED | FAILED.
 *  - retryCount + lastAttemptAt support exponential-backoff retry strategy.
 *  - @Version enables optimistic locking to prevent concurrent state corruption.
 */
@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payments_idempotency_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_payments_status",          columnList = "status"),
        @Index(name = "idx_payments_source_account",  columnList = "source_account_id"),
        @Index(name = "idx_payments_created_at",      columnList = "created_at")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Client-generated idempotency key. If a request arrives with an
     *  existing key we return the stored result — no duplicate processing. */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "source_account_id", nullable = false, length = 64)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, length = 64)
    private String destinationAccountId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /** Populated only on FAILED status — kept for auditability. */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Optimistic locking: prevents two concurrent threads from transitioning
     *  the same payment from PROCESSING into different terminal states. */
    @Version
    private Long version;

    // ── State machine transitions ──────────────────────────────────────────

    public void markProcessing() {
        validateTransition(PaymentStatus.PROCESSING);
        this.status = PaymentStatus.PROCESSING;
        this.lastAttemptAt = Instant.now();
    }

    public void markCompleted() {
        validateTransition(PaymentStatus.COMPLETED);
        this.status = PaymentStatus.COMPLETED;
    }

    public void markFailed(String reason) {
        validateTransition(PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.retryCount++;
    }

    private void validateTransition(PaymentStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                String.format("Cannot transition payment %s from %s to %s", id, this.status, target));
        }
    }
}
