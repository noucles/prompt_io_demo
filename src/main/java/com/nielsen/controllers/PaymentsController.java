package com.nielsen.controllers;

import com.nielsen.domain.PaymentStatus;
import com.nielsen.dto.CreatePaymentRequest;
import com.nielsen.dto.PaymentResponse;
import com.nielsen.services.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentsController {

  private final PaymentService paymentService;

  public PaymentsController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @PostMapping
  public ResponseEntity<PaymentResponse> createPayment(
      @Valid @RequestBody CreatePaymentRequest request,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PaymentResponse.from(
                paymentService.create(request.userId(), request.amount(), idempotencyKey)));
  }

  @GetMapping("/{id}")
  public PaymentResponse getPayment(@PathVariable UUID id) {
    return new PaymentResponse(
        UUID.randomUUID(), "userId", 123L, PaymentStatus.SUCCEEDED, Instant.now());
  }
}
