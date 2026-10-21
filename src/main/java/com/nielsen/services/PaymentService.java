package com.nielsen.services;

import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

  public Payment create(String userId, long amount, String idempotencyKey) {
    return new Payment(
        UUID.randomUUID(),
        userId,
        amount,
        PaymentStatus.PENDING,
        Instant.now(),
        idempotencyKey,
        Instant.now());
  }
}
