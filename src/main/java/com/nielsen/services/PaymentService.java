package com.nielsen.services;

import com.nielsen.domain.IdempotencyKeyConflictException;
import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import com.nielsen.repository.PaymentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
  private final PaymentRepository paymentRepository;

  public PaymentService(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  public Payment create(String userId, long amount, String idempotencyKey) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var newPayment =
        new Payment(
            UUID.randomUUID(), userId, amount, PaymentStatus.PENDING, now, idempotencyKey, now);
    var created = paymentRepository.insertIfAbsent(newPayment);

    if (created) {
      return newPayment;
    }

    var existingPayment =
        paymentRepository
            .findByIdempotencyKey(idempotencyKey)
            .orElseThrow(
                () -> new IllegalStateException("Payment missing after idempotency key conflict"));
    if (!existingPayment.userId().equals(userId) || existingPayment.amount() != amount) {
      throw new IdempotencyKeyConflictException();
    }
    return existingPayment;
  }

  public Optional<Payment> findById(UUID id) {
    return paymentRepository.findById(id);
  }
}
