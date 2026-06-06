package com.rajasekhar.payment.service;

import com.rajasekhar.payment.domain.model.Payment;
import com.rajasekhar.payment.dto.request.PaymentRequest;
import com.rajasekhar.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled retry service for FAILED payments.
 *
 * Retry strategy: exponential backoff.
 *   - Attempt 1: retry after 2 minutes
 *   - Attempt 2: retry after 4 minutes
 *   - Attempt 3: retry after 8 minutes
 *   - Beyond MAX_RETRIES: payment is abandoned (terminal FAILED state)
 *
 * This runs every 60 seconds and picks up eligible payments.
 * In a multi-instance deployment, the DB query + optimistic locking on Payment
 * prevents two instances from retrying the same payment concurrently.
 *
 * Production enhancement: replace with a proper job-lock mechanism
 * (e.g., ShedLock or Quartz) to ensure only one instance runs the scheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRetryService {

    private static final int MAX_RETRIES = 3;
    private static final int BASE_BACKOFF_MINUTES = 2;

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 60_000)
    public void retryFailedPayments() {
        Instant cutoff = Instant.now().minus(BASE_BACKOFF_MINUTES, ChronoUnit.MINUTES);
        List<Payment> retryable = paymentRepository.findRetryablePayments(MAX_RETRIES, cutoff);

        if (retryable.isEmpty()) return;

        log.info("RetryScheduler: found {} payments eligible for retry", retryable.size());

        for (Payment payment : retryable) {
            try {
                long backoffMinutes = (long) BASE_BACKOFF_MINUTES * (1L << payment.getRetryCount());
                Instant nextEligibleAt = payment.getLastAttemptAt() != null
                    ? payment.getLastAttemptAt().plus(backoffMinutes, ChronoUnit.MINUTES)
                    : Instant.now();

                if (Instant.now().isBefore(nextEligibleAt)) {
                    log.debug("Payment {} not yet eligible for retry (next: {})",
                        payment.getId(), nextEligibleAt);
                    continue;
                }

                log.info("Retrying payment: id={}, attempt={}", payment.getId(), payment.getRetryCount() + 1);
                paymentService.initiatePayment(new PaymentRequest(
                    payment.getIdempotencyKey() + "_retry_" + (payment.getRetryCount() + 1),
                    payment.getSourceAccountId(),
                    payment.getDestinationAccountId(),
                    payment.getAmount(),
                    payment.getCurrency()
                ));

            } catch (Exception ex) {
                log.error("Retry failed for payment {}: {}", payment.getId(), ex.getMessage());
            }
        }
    }
}
