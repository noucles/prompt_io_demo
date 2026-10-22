package com.nielsen.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

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

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"SUCCEEDED", "FAILED"})
  void completesPendingPaymentAndPreventsFurtherChanges(PaymentStatus status) {
    Payment payment = payment("key-1", 1050L);
    repository.insertIfAbsent(payment);
    Instant updatedAt = payment.updatedAt().plusSeconds(60);
    String reference = status == PaymentStatus.SUCCEEDED ? "sim_reference" : null;
    String reason = status == PaymentStatus.FAILED ? "insufficient_funds" : null;

    assertThat(repository.completeIfPending(payment.id(), status, reference, reason, updatedAt))
        .isTrue();
    var completed =
        new Payment(
            payment.id(),
            payment.userId(),
            payment.amount(),
            status,
            payment.createdAt(),
            payment.idempotencyKey(),
            updatedAt,
            reference,
            reason);
    assertThat(repository.findById(payment.id())).contains(completed);

    PaymentStatus otherStatus =
        status == PaymentStatus.SUCCEEDED ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
    assertThat(
            repository.completeIfPending(
                payment.id(),
                otherStatus,
                otherStatus == PaymentStatus.SUCCEEDED ? "another_reference" : null,
                otherStatus == PaymentStatus.FAILED ? "another_reason" : null,
                updatedAt.plusSeconds(60)))
        .isFalse();
    assertThat(repository.findById(payment.id())).contains(completed);
  }

  @ParameterizedTest
  @MethodSource("inconsistentOutcomes")
  void rejectsInconsistentOutcomes(PaymentStatus status, String reference, String reason) {
    Payment payment = payment("key-1", 1050L);
    repository.insertIfAbsent(payment);

    assertThatThrownBy(
            () ->
                repository.completeIfPending(
                    payment.id(), status, reference, reason, payment.updatedAt()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  static Stream<Arguments> inconsistentOutcomes() {
    return Stream.of(
        Arguments.of(PaymentStatus.SUCCEEDED, null, null),
        Arguments.of(PaymentStatus.FAILED, null, null),
        Arguments.of(PaymentStatus.PENDING, "unexpected_reference", null),
        Arguments.of(PaymentStatus.PENDING, null, "unexpected_reason"),
        Arguments.of(PaymentStatus.SUCCEEDED, "sim_reference", "unexpected_reason"),
        Arguments.of(PaymentStatus.FAILED, "unexpected_reference", "insufficient_funds"));
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
        createdAt.plusSeconds(60),
        null,
        null);
  }
}
