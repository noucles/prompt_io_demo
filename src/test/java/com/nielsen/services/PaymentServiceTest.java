package com.nielsen.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.domain.IdempotencyKeyConflictException;
import com.nielsen.domain.PaymentStatus;
import com.nielsen.processor.SimulatedPaymentProcessor;
import com.nielsen.repository.PaymentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@JdbcTest
@Import({
  TestcontainersConfiguration.class,
  PaymentRepository.class,
  PaymentService.class,
  SimulatedPaymentProcessor.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentServiceTest {
  @Autowired PaymentService service;
  @Autowired PaymentRepository repository;
  @Autowired JdbcClient jdbcClient;
  @MockitoSpyBean SimulatedPaymentProcessor processor;

  // These tests commit statements as production does, so clean the isolated test database
  // explicitly.
  @BeforeEach
  @AfterEach
  void cleanPayments() {
    jdbcClient.sql("DELETE FROM payments").update();
  }

  @Test
  void createsSucceededPayment() {
    var payment = service.create("user-1", 1050L, "key-1");

    assertThat(payment.userId()).isEqualTo("user-1");
    assertThat(payment.amount()).isEqualTo(1050L);
    assertThat(payment.idempotencyKey()).isEqualTo("key-1");
    assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(payment.processorReference()).startsWith("sim_");
    assertThat(payment.failureReason()).isNull();
    assertThat(payment.updatedAt()).isAfterOrEqualTo(payment.createdAt());
    assertThat(service.findById(payment.id())).contains(payment);
  }

  @Test
  void declineCreatesFailedPayment() {
    var payment = service.create("user-1", 1001L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.failureReason()).isEqualTo("insufficient_funds");
    assertThat(payment.processorReference()).isNull();
    assertThat(service.findById(payment.id())).contains(payment);
  }

  @Test
  void unavailableProcessorLeavesPaymentPending() {
    var payment = service.create("user-1", 1002L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.processorReference()).isNull();
    assertThat(payment.failureReason()).isNull();
    assertThat(payment.updatedAt()).isEqualTo(payment.createdAt());
    assertThat(service.findById(payment.id())).contains(payment);
  }

  @ParameterizedTest
  @ValueSource(longs = {1050, 1001, 1002})
  void sameKeyAndBodyReturnsExistingPaymentWithoutChargingAgain(long amount) {
    var first = service.create("user-1", amount, "key-1");

    var replay = service.create("user-1", amount, "key-1");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay).isEqualTo(first);
    assertThat(jdbcClient.sql("SELECT count(*) FROM payments").query(Long.class).single())
        .isEqualTo(1L);
    verify(processor, times(1)).charge(first.id(), "user-1", amount);
  }

  @Test
  void differentAmountConflicts() {
    var first = service.create("user-1", 1050L, "key-1");

    assertThatThrownBy(() -> service.create("user-1", 2000L, "key-1"))
        .isInstanceOf(IdempotencyKeyConflictException.class);

    assertThat(service.findById(first.id())).contains(first);
    verify(processor, times(1)).charge(any(), anyString(), anyLong());
  }

  @Test
  void differentUserConflicts() {
    var first = service.create("user-1", 1050L, "key-1");

    assertThatThrownBy(() -> service.create("user-2", 1050L, "key-1"))
        .isInstanceOf(IdempotencyKeyConflictException.class);

    assertThat(service.findById(first.id())).contains(first);
    verify(processor, times(1)).charge(any(), anyString(), anyLong());
  }

  @Test
  void replayReturnsCurrentStatus() {
    var first = service.create("user-1", 1002L, "key-1");
    assertThat(
            repository.completeIfPending(
                first.id(),
                PaymentStatus.SUCCEEDED,
                "recovered-reference",
                null,
                Instant.now().truncatedTo(ChronoUnit.MICROS)))
        .isTrue();

    var replay = service.create("user-1", 1002L, "key-1");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(replay.processorReference()).isEqualTo("recovered-reference");
    assertThat(service.findById(first.id())).contains(replay);
    verify(processor, times(1)).charge(first.id(), "user-1", 1002L);
  }

  @Test
  void insertCommitsBeforeProcessorCallAndNoTransactionIsOpen() {
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              UUID id = invocation.getArgument(0);
              var stored = repository.findById(id).orElseThrow();
              assertThat(stored.status()).isEqualTo(PaymentStatus.PENDING);
              return invocation.callRealMethod();
            })
        .when(processor)
        .charge(any(), anyString(), anyLong());

    var payment = service.create("user-1", 1050L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    verify(processor).charge(payment.id(), "user-1", 1050L);
  }

  @Test
  void returnsStoredStateIfAnotherProcessCompletedPayment() {
    doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              UUID id = invocation.getArgument(0);
              assertThat(
                      repository.completeIfPending(
                          id,
                          PaymentStatus.FAILED,
                          null,
                          "recovered_decline",
                          Instant.now().truncatedTo(ChronoUnit.MICROS)))
                  .isTrue();
              return result;
            })
        .when(processor)
        .charge(any(), anyString(), anyLong());

    var payment = service.create("user-1", 1050L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.failureReason()).isEqualTo("recovered_decline");
    assertThat(payment.processorReference()).isNull();
    assertThat(service.findById(payment.id())).contains(payment);
  }

  @Test
  void unknownIdReturnsEmpty() {
    assertThat(service.findById(UUID.randomUUID())).isEmpty();
  }
}
