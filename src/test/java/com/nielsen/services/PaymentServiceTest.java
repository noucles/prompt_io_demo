package com.nielsen.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.domain.IdempotencyKeyConflictException;
import com.nielsen.domain.PaymentStatus;
import com.nielsen.repository.PaymentRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import({TestcontainersConfiguration.class, PaymentRepository.class, PaymentService.class})
class PaymentServiceTest {
  @Autowired PaymentService service;
  @Autowired JdbcClient jdbcClient;

  @Test
  void createsPendingPayment() {
    var payment = service.create("user-1", 1050L, "key-1");

    assertThat(payment.userId()).isEqualTo("user-1");
    assertThat(payment.amount()).isEqualTo(1050L);
    assertThat(payment.idempotencyKey()).isEqualTo("key-1");
    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.updatedAt()).isEqualTo(payment.createdAt());
    assertThat(service.findById(payment.id())).contains(payment);
  }

  @Test
  void sameKeyAndBodyReturnsExistingPayment() {
    var first = service.create("user-1", 1050L, "key-1");

    var replay = service.create("user-1", 1050L, "key-1");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay).isEqualTo(first);
    assertThat(jdbcClient.sql("SELECT count(*) FROM payments").query(Long.class).single())
        .isEqualTo(1L);
  }

  @Test
  void differentAmountConflicts() {
    var first = service.create("user-1", 1050L, "key-1");

    assertThatThrownBy(() -> service.create("user-1", 2000L, "key-1"))
        .isInstanceOf(IdempotencyKeyConflictException.class);

    assertThat(service.findById(first.id())).contains(first);
  }

  @Test
  void differentUserConflicts() {
    var first = service.create("user-1", 1050L, "key-1");

    assertThatThrownBy(() -> service.create("user-2", 1050L, "key-1"))
        .isInstanceOf(IdempotencyKeyConflictException.class);

    assertThat(service.findById(first.id())).contains(first);
  }

  @Test
  void replayReturnsCurrentStatus() {
    var first = service.create("user-1", 1050L, "key-1");
    jdbcClient
        .sql("UPDATE payments SET status = 'SUCCEEDED' WHERE id = :id")
        .param("id", first.id())
        .update();

    var replay = service.create("user-1", 1050L, "key-1");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(service.findById(first.id())).contains(replay);
  }

  @Test
  void unknownIdReturnsEmpty() {
    assertThat(service.findById(UUID.randomUUID())).isEmpty();
  }
}
