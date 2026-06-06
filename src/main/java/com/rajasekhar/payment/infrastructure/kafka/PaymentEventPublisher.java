package com.rajasekhar.payment.infrastructure.kafka;

import com.rajasekhar.payment.domain.event.PaymentCompletedEvent;
import com.rajasekhar.payment.domain.event.PaymentInitiatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka event publisher for the payment domain.
 *
 * Design choices:
 *  - Keyed messages: paymentId is used as the Kafka partition key, guaranteeing
 *    that all events for the same payment land on the same partition and are
 *    consumed in order by downstream services.
 *  - Async send with callback: we don't block the payment thread on Kafka ack.
 *    Instead we log failures — a separate outbox pattern or retry scheduler
 *    handles guaranteed delivery in production-grade setups.
 *  - Topic names via @Value: environment-specific routing without code changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.payment-initiated}")
    private String paymentInitiatedTopic;

    @Value("${app.kafka.topics.payment-completed}")
    private String paymentCompletedTopic;

    public void publishPaymentInitiated(PaymentInitiatedEvent event) {
        String key = event.paymentId().toString();
        CompletableFuture<SendResult<String, Object>> future =
            kafkaTemplate.send(paymentInitiatedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish PaymentInitiatedEvent for paymentId={} key={}: {}",
                    event.paymentId(), key, ex.getMessage());
                // In production: write to outbox table for guaranteed delivery
            } else {
                log.info("PaymentInitiatedEvent published: paymentId={}, partition={}, offset={}",
                    event.paymentId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            }
        });
    }

    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        String key = event.paymentId().toString();
        kafkaTemplate.send(paymentCompletedTopic, key, event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish PaymentCompletedEvent: paymentId={}, error={}",
                        event.paymentId(), ex.getMessage());
                } else {
                    log.info("PaymentCompletedEvent published: paymentId={}", event.paymentId());
                }
            });
    }
}
