package com.rajasekhar.payment.repository;

import com.rajasekhar.payment.domain.model.Payment;
import com.rajasekhar.payment.domain.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findBySourceAccountIdAndStatus(String sourceAccountId, PaymentStatus status);

    /** Fetch payments eligible for retry: FAILED, retryCount < maxRetries,
     *  not attempted in the last backoff window. Used by the retry scheduler. */
    @Query("""
        SELECT p FROM Payment p
        WHERE p.status = 'FAILED'
          AND p.retryCount < :maxRetries
          AND (p.lastAttemptAt IS NULL OR p.lastAttemptAt < :cutoff)
        ORDER BY p.lastAttemptAt ASC NULLS FIRST
        """)
    List<Payment> findRetryablePayments(
        @Param("maxRetries") int maxRetries,
        @Param("cutoff") Instant cutoff
    );

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.status = :status")
    long countByStatus(@Param("status") PaymentStatus status);
}
