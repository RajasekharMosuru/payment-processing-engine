package com.rajasekhar.payment.controller;

import com.rajasekhar.payment.dto.request.PaymentRequest;
import com.rajasekhar.payment.dto.response.PaymentResponse;
import com.rajasekhar.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment processing endpoints")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(
        summary = "Initiate a payment",
        description = "Initiates a new payment. Idempotent — duplicate requests with the same " +
                      "idempotencyKey return the original response without re-processing."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Payment initiated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "409", description = "Duplicate idempotency key"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
        @ApiResponse(responseCode = "500", description = "Payment gateway error")
    })
    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @Valid @RequestBody PaymentRequest request) {
        log.info("POST /api/v1/payments — idempotencyKey={}", request.idempotencyKey());
        PaymentResponse response = paymentService.initiatePayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get payment by ID")
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @Parameter(description = "Payment UUID") @PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    @Operation(
        summary = "Get payment by idempotency key",
        description = "Useful for clients to retrieve the result of a previous payment attempt."
    )
    @GetMapping("/idempotency/{idempotencyKey}")
    public ResponseEntity<PaymentResponse> getByIdempotencyKey(
            @PathVariable String idempotencyKey) {
        return ResponseEntity.ok(paymentService.getPaymentByIdempotencyKey(idempotencyKey));
    }
}
