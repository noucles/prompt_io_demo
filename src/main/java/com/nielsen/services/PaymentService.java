package com.nielsen.services;

import com.nielsen.domain.IdempotencyKeyConflictException;
import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import com.nielsen.outbox.OutboxRepository;
import com.nielsen.outbox.PaymentEvent;
import com.nielsen.processor.PaymentProcessor;
import com.nielsen.processor.ProcessorResult;
import com.nielsen.processor.ProcessorUnavailableException;
import com.nielsen.repository.PaymentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  private final PaymentRepository paymentRepository;
  private final PaymentProcessor processor;
  private final OutboxRepository outboxRepository;
  private final TransactionTemplate transactionTemplate;

  public PaymentService(
      PaymentRepository paymentRepository,
      PaymentProcessor processor,
      OutboxRepository outboxRepository,
      TransactionTemplate transactionTemplate) {
    this.paymentRepository = paymentRepository;
    this.processor = processor;
    this.outboxRepository = outboxRepository;
    this.transactionTemplate = transactionTemplate;
  }

  public Payment create(String userId, long amount, String idempotencyKey) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var newPayment =
        new Payment(
            UUID.randomUUID(),
            userId,
            amount,
            PaymentStatus.PENDING,
            now,
            idempotencyKey,
            now,
            null,
            null);
    var created = paymentRepository.insertIfAbsent(newPayment);

    if (created) {
      return processPayment(newPayment);
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

  private Payment processPayment(Payment payment) {
    // The insert has committed; no database transaction spans the processor call.
    ProcessorResult result;
    try {
      result = processor.charge(payment.id(), payment.userId(), payment.amount());
    } catch (ProcessorUnavailableException exception) {
      log.warn(
          "Processor outcome unknown for payment {}: {}", payment.id(), exception.getMessage());
      return payment;
    }

    Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    PaymentStatus newStatus;
    String processorReference;
    String failureReason;
    switch (result) {
      case ProcessorResult.Approved approved -> {
        newStatus = PaymentStatus.SUCCEEDED;
        processorReference = approved.processorReference();
        failureReason = null;
      }
      case ProcessorResult.Declined declined -> {
        newStatus = PaymentStatus.FAILED;
        processorReference = null;
        failureReason = declined.reason();
      }
    }
    PaymentEvent.Type eventType =
        newStatus == PaymentStatus.SUCCEEDED
            ? PaymentEvent.Type.PAYMENT_SUCCEEDED
            : PaymentEvent.Type.PAYMENT_FAILED;
    Boolean completed =
        transactionTemplate.execute(
            transaction -> {
              boolean transitioned =
                  paymentRepository.completeIfPending(
                      payment.id(), newStatus, processorReference, failureReason, updatedAt);
              if (transitioned) {
                outboxRepository.insert(
                    new PaymentEvent(
                        UUID.randomUUID(),
                        eventType,
                        payment.id(),
                        payment.userId(),
                        payment.amount(),
                        updatedAt));
              }
              return transitioned;
            });
    if (!Boolean.TRUE.equals(completed)) {
      log.warn("Payment {} is no longer pending; returning its stored state", payment.id());
    }
    return paymentRepository
        .findById(payment.id())
        .orElseThrow(() -> new IllegalStateException("Payment missing after processor call"));
  }
}
