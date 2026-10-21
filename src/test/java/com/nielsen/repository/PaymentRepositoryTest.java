package com.nielsen.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import({TestcontainersConfiguration.class, PaymentRepository.class})
class PaymentRepositoryTest {
  @Autowired PaymentRepository repository;

  @Test
  void roundTripsPayment() {
    Payment payment = payment("key-1", 1050L);

    assertThat(repository.insertIfAbsent(payment)).isTrue();

    assertThat(repository.findById(payment.id())).contains(payment);
  }

  @Test
  void duplicateKeyPreservesFirstPayment() {
    Payment first = payment("key-1", 1050L);
    Payment second = payment("key-1", 2000L);

    assertThat(repository.insertIfAbsent(first)).isTrue();
    assertThat(repository.insertIfAbsent(second)).isFalse();

    assertThat(repository.findByIdempotencyKey(first.idempotencyKey())).contains(first);
    assertThat(repository.findById(second.id())).isEmpty();
  }

  @Test
  void findsPaymentByIdempotencyKey() {
    Payment payment = payment("key-1", 1050L);
    repository.insertIfAbsent(payment("key-2", 2000L));
    repository.insertIfAbsent(payment);

    assertThat(repository.findByIdempotencyKey(payment.idempotencyKey())).contains(payment);
  }

  @Test
  void unknownIdReturnsEmpty() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  private static Payment payment(String key, long amount) {
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return new Payment(
        UUID.randomUUID(),
        "user-1",
        amount,
        PaymentStatus.PENDING,
        createdAt,
        key,
        createdAt.plusSeconds(60));
  }
}
