package com.rajasekhar.payment.service;

import com.rajasekhar.payment.domain.event.PaymentCompletedEvent;
import com.rajasekhar.payment.domain.event.PaymentInitiatedEvent;
import com.rajasekhar.payment.domain.model.Payment;
import com.rajasekhar.payment.domain.model.PaymentStatus;
import com.rajasekhar.payment.dto.request.PaymentRequest;
import com.rajasekhar.payment.dto.response.PaymentResponse;
import com.rajasekhar.payment.exception.DuplicatePaymentException;
import com.rajasekhar.payment.exception.PaymentNotFoundException;
import com.rajasekhar.payment.exception.PaymentProcessingException;
import com.rajasekhar.payment.infrastructure.kafka.PaymentEventPublisher;
import com.rajasekhar.payment.infrastructure.redis.IdempotencyStore;
import com.rajasekhar.payment.repository.PaymentRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Payment saga orchestrator.
 *
 * Saga steps for initiatePayment():
 *   1. Idempotency check (Redis fast path) — return cached result if duplicate.
 *   2. Persist payment in INITIATED state (DB unique constraint as safety net).
 *   3. Store idempotency mapping in Redis.
 *   4. Transition to PROCESSING, publish PaymentInitiatedEvent to Kafka.
 *   5. Simulate downstream processing (replace with actual bank/UPI gateway call).
 *   6. Transition to COMPLETED, publish PaymentCompletedEvent.
 *
 * Compensating transactions:
 *   If step 5/6 fails -> markFailed() rolls back the saga to FAILED state.
 *   A scheduled RetryService re-enqueues FAILED payments with exponential backoff.
 *
 * Resilience:
 *   @CircuitBreaker wraps the downstream processing call — if the external
 *   payment gateway fails repeatedly, the circuit opens and we fail fast
 *   instead of cascading failures across the system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final int MAX_RETRY_COUNT = 3;

    private final PaymentRepository paymentRepository;
    private final IdempotencyStore idempotencyStore;
    private final PaymentEventPublisher eventPublisher;

    /**
     * Initiate a new payment with idempotency guarantee.
     *
     * @RateLimiter: Limits to 100 req/s per service instance (configured in application.yml).
     *               In production, move this to API Gateway for distributed rate limiting.
     */
    @Transactional
    @RateLimiter(name = "paymentRateLimiter", fallbackMethod = "rateLimitFallback")
    public PaymentResponse initiatePayment(PaymentRequest request) {
        log.info("Initiating payment: idempotencyKey={}, source={}, amount={}",
            request.idempotencyKey(), request.sourceAccountId(), request.amount());

        // ── Step 1: Idempotency check (Redis fast path) ───────────────────
        var cachedPaymentId = idempotencyStore.getCachedPaymentId(request.idempotencyKey());
        if (cachedPaymentId.isPresent()) {
            log.info("Duplicate payment detected via Redis cache: idempotencyKey={}", request.idempotencyKey());
            return paymentRepository.findById(UUID.fromString(cachedPaymentId.get()))
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException("Cached payment not found in DB"));
        }

        // DB-level check (handles race between two concurrent identical requests)
        if (paymentRepository.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            throw new DuplicatePaymentException(
                "Payment with idempotency key already exists: " + request.idempotencyKey());
        }

        // ── Step 2: Persist in INITIATED state ────────────────────────────
        Payment payment = Payment.builder()
            .idempotencyKey(request.idempotencyKey())
            .sourceAccountId(request.sourceAccountId())
            .destinationAccountId(request.destinationAccountId())
            .amount(request.amount())
            .currency(request.currency())
            .status(PaymentStatus.INITIATED)
            .build();

        payment = paymentRepository.save(payment);
        log.info("Payment persisted: id={}, status=INITIATED", payment.getId());

        // ── Step 3: Cache idempotency key → payment ID ───────────────────
        idempotencyStore.storeIfAbsent(request.idempotencyKey(), payment.getId().toString());

        // ── Steps 4–6: Saga execution ─────────────────────────────────────
        return executePaymentSaga(payment);
    }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "paymentGatewayFallback")
    private PaymentResponse executePaymentSaga(Payment payment) {
        try {
            // Step 4: Transition to PROCESSING + publish initiated event
            payment.markProcessing();
            paymentRepository.save(payment);

            eventPublisher.publishPaymentInitiated(PaymentInitiatedEvent.of(
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getSourceAccountId(),
                payment.getDestinationAccountId(),
                payment.getAmount(),
                payment.getCurrency()
            ));

            // Step 5: Call downstream payment gateway (stub — wire to actual gateway)
            processWithGateway(payment);

            // Step 6: Mark completed + publish completed event
            payment.markCompleted();
            payment = paymentRepository.save(payment);

            eventPublisher.publishPaymentCompleted(new PaymentCompletedEvent(
                payment.getId(),
                payment.getSourceAccountId(),
                payment.getDestinationAccountId(),
                payment.getAmount(),
                payment.getCurrency(),
                Instant.now()
            ));

            log.info("Payment completed successfully: id={}", payment.getId());
            return PaymentResponse.from(payment);

        } catch (Exception ex) {
            // Compensating transaction: mark failed, increment retry count
            log.error("Payment saga failed: id={}, error={}", payment.getId(), ex.getMessage());
            payment.markFailed(ex.getMessage());
            paymentRepository.save(payment);
            throw new PaymentProcessingException("Payment processing failed", ex);
        }
    }

    /**
     * Stub gateway call — replace with actual UPI/bank/NEFT gateway integration.
     * In production this is where you'd call Razorpay, NPCI, or internal ledger.
     */
    private void processWithGateway(Payment payment) {
        // TODO: integrate with payment gateway (Razorpay, PayU, NPCI UPI, internal ledger)
        // Throw PaymentGatewayException on downstream failure
        log.debug("Processing payment with gateway: id={}", payment.getId());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
            .map(PaymentResponse::from)
            .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByIdempotencyKey(String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
            .map(PaymentResponse::from)
            .orElseThrow(() -> new PaymentNotFoundException(
                "Payment not found for idempotency key: " + idempotencyKey));
    }

    // ── Resilience fallbacks ───────────────────────────────────────────────

    public PaymentResponse rateLimitFallback(PaymentRequest request, Throwable t) {
        log.warn("Rate limit exceeded for source account: {}", request.sourceAccountId());
        throw new PaymentProcessingException("Too many requests. Please retry after a moment.", t);
    }

    public PaymentResponse paymentGatewayFallback(Payment payment, Throwable t) {
        log.error("Circuit breaker OPEN — payment gateway unavailable: id={}", payment.getId());
        payment.markFailed("Payment gateway unavailable — circuit breaker open");
        paymentRepository.save(payment);
        throw new PaymentProcessingException("Payment gateway temporarily unavailable. Payment queued for retry.", t);
    }
}
