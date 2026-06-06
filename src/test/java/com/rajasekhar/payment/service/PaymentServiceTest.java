package com.rajasekhar.payment.service;

import com.rajasekhar.payment.domain.model.Payment;
import com.rajasekhar.payment.domain.model.PaymentStatus;
import com.rajasekhar.payment.dto.request.PaymentRequest;
import com.rajasekhar.payment.dto.response.PaymentResponse;
import com.rajasekhar.payment.exception.DuplicatePaymentException;
import com.rajasekhar.payment.infrastructure.kafka.PaymentEventPublisher;
import com.rajasekhar.payment.infrastructure.redis.IdempotencyStore;
import com.rajasekhar.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService unit tests")
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private IdempotencyStore idempotencyStore;
    @Mock private PaymentEventPublisher eventPublisher;

    @InjectMocks
    private PaymentService paymentService;

    private PaymentRequest validRequest;
    private Payment savedPayment;

    @BeforeEach
    void setUp() {
        validRequest = new PaymentRequest(
            "test-idempotency-key-001",
            "ACC-SOURCE-001",
            "ACC-DEST-001",
            new BigDecimal("1000.00"),
            "INR"
        );

        savedPayment = Payment.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(validRequest.idempotencyKey())
            .sourceAccountId(validRequest.sourceAccountId())
            .destinationAccountId(validRequest.destinationAccountId())
            .amount(validRequest.amount())
            .currency(validRequest.currency())
            .status(PaymentStatus.INITIATED)
            .build();
    }

    @Test
    @DisplayName("Should successfully initiate a new payment")
    void shouldInitiatePayment_whenRequestIsValid() {
        when(idempotencyStore.getCachedPaymentId(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);
        when(idempotencyStore.storeIfAbsent(any(), any())).thenReturn(true);

        PaymentResponse response = paymentService.initiatePayment(validRequest);

        assertThat(response).isNotNull();
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(idempotencyStore).storeIfAbsent(
            eq(validRequest.idempotencyKey()), any());
    }

    @Test
    @DisplayName("Should return cached response for duplicate idempotency key")
    void shouldReturnCachedResponse_whenDuplicateIdempotencyKey() {
        String cachedPaymentId = savedPayment.getId().toString();
        when(idempotencyStore.getCachedPaymentId(validRequest.idempotencyKey()))
            .thenReturn(Optional.of(cachedPaymentId));
        when(paymentRepository.findById(UUID.fromString(cachedPaymentId)))
            .thenReturn(Optional.of(savedPayment));

        PaymentResponse response = paymentService.initiatePayment(validRequest);

        assertThat(response.idempotencyKey()).isEqualTo(validRequest.idempotencyKey());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw DuplicatePaymentException when DB has existing idempotency key")
    void shouldThrowDuplicateException_whenDbHasExistingKey() {
        when(idempotencyStore.getCachedPaymentId(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey(validRequest.idempotencyKey()))
            .thenReturn(Optional.of(savedPayment));

        assertThatThrownBy(() -> paymentService.initiatePayment(validRequest))
            .isInstanceOf(DuplicatePaymentException.class)
            .hasMessageContaining(validRequest.idempotencyKey());
    }

    @Test
    @DisplayName("Payment status transitions should follow the state machine")
    void shouldFollowStateMachine() {
        Payment payment = Payment.builder()
            .id(UUID.randomUUID())
            .status(PaymentStatus.INITIATED)
            .build();

        payment.markProcessing();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        payment.markCompleted();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        // Terminal state — cannot transition further
        assertThatThrownBy(() -> payment.markProcessing())
            .isInstanceOf(IllegalStateException.class);
    }
}
