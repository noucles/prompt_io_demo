package com.nielsen.dto;

import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    String userId,
    long amount,
    PaymentStatus status,
    Instant createdAt,
    String processorReference,
    String failureReason) {
  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.id(),
        payment.userId(),
        payment.amount(),
        payment.status(),
        payment.createdAt(),
        payment.processorReference(),
        payment.failureReason());
  }
}
