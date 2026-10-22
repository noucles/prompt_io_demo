package com.nielsen.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.outbox.OutboxRepository;
import com.nielsen.outbox.PaymentEvent;
import com.nielsen.processor.SimulatedPaymentProcessor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@JdbcTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
  TestcontainersConfiguration.class,
  PaymentRepository.class,
  PaymentService.class,
  SimulatedPaymentProcessor.class,
  OutboxRepository.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentServiceTest {
  @Autowired PaymentService service;
  @Autowired PaymentRepository repository;
  @Autowired JdbcClient jdbcClient;
  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate transactionTemplate;
  @MockitoSpyBean SimulatedPaymentProcessor processor;
  @MockitoSpyBean OutboxRepository outbox;

  // These tests commit statements as production does, so clean the isolated test database
  // explicitly.
  @BeforeEach
  @AfterEach
  void cleanPayments() {
    jdbcClient.sql("DELETE FROM payment_events").update();
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
    assertEvent(payment, PaymentEvent.Type.PAYMENT_SUCCEEDED);
  }

  @Test
  void declineCreatesFailedPayment() {
    var payment = service.create("user-1", 1001L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.failureReason()).isEqualTo("insufficient_funds");
    assertThat(payment.processorReference()).isNull();
    assertThat(service.findById(payment.id())).contains(payment);
    assertEvent(payment, PaymentEvent.Type.PAYMENT_FAILED);
  }

  @Test
  void unavailableProcessorLeavesPaymentPending() {
    var payment = service.create("user-1", 1002L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.processorReference()).isNull();
    assertThat(payment.failureReason()).isNull();
    assertThat(payment.updatedAt()).isEqualTo(payment.createdAt());
    assertThat(service.findById(payment.id())).contains(payment);
    assertThat(eventCount()).isZero();
    verify(outbox, never()).insert(any());
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
    if (first.status() == PaymentStatus.PENDING) {
      assertThat(eventCount()).isZero();
      verify(outbox, never()).insert(any());
    } else {
      assertThat(eventCount()).isEqualTo(1L);
      verify(outbox, times(1)).insert(any());
    }
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
    Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    transactionTemplate.executeWithoutResult(
        transaction -> {
          assertThat(
                  repository.completeIfPending(
                      first.id(), PaymentStatus.SUCCEEDED, "recovered-reference", null, occurredAt))
              .isTrue();
          outbox.insert(
              new PaymentEvent(
                  UUID.randomUUID(),
                  PaymentEvent.Type.PAYMENT_SUCCEEDED,
                  first.id(),
                  first.userId(),
                  first.amount(),
                  occurredAt));
        });

    var replay = service.create("user-1", 1002L, "key-1");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(replay.processorReference()).isEqualTo("recovered-reference");
    assertThat(service.findById(first.id())).contains(replay);
    verify(processor, times(1)).charge(first.id(), "user-1", 1002L);
    assertEvent(replay, PaymentEvent.Type.PAYMENT_SUCCEEDED);
    verify(outbox, times(1)).insert(any());
  }

  @Test
  void insertCommitsBeforeProcessorCallAndNoTransactionIsOpen() {
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              UUID id = invocation.getArgument(0);
              var stored = repository.findById(id).orElseThrow();
              assertThat(stored.status()).isEqualTo(PaymentStatus.PENDING);
              assertThat(eventCount()).isZero();
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
              Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
              transactionTemplate.executeWithoutResult(
                  transaction -> {
                    assertThat(
                            repository.completeIfPending(
                                id, PaymentStatus.FAILED, null, "recovered_decline", occurredAt))
                        .isTrue();
                    outbox.insert(
                        new PaymentEvent(
                            UUID.randomUUID(),
                            PaymentEvent.Type.PAYMENT_FAILED,
                            id,
                            "user-1",
                            1050L,
                            occurredAt));
                  });
              return result;
            })
        .when(processor)
        .charge(any(), anyString(), anyLong());

    var payment = service.create("user-1", 1050L, "key-1");

    assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.failureReason()).isEqualTo("recovered_decline");
    assertThat(payment.processorReference()).isNull();
    assertThat(service.findById(payment.id())).contains(payment);
    assertEvent(payment, PaymentEvent.Type.PAYMENT_FAILED);
    verify(outbox, times(1)).insert(any());
  }

  @ParameterizedTest
  @ValueSource(longs = {1050, 1001})
  void outboxFailureRollsBackCompletion(long amount) {
    var failure = new DataIntegrityViolationException("Simulated outbox insert failure");
    doThrow(failure).when(outbox).insert(any());

    assertThatThrownBy(() -> service.create("user-1", amount, "key-1")).isSameAs(failure);

    var payment = repository.findByIdempotencyKey("key-1").orElseThrow();
    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.processorReference()).isNull();
    assertThat(payment.failureReason()).isNull();
    assertThat(payment.updatedAt()).isEqualTo(payment.createdAt());
    assertThat(eventCount()).isZero();
    verify(outbox).insert(any());
    verify(processor).charge(payment.id(), "user-1", amount);
  }

  @Test
  void failureAfterEventInsertRollsBackBothWrites() {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("Failure after event insert");
            })
        .when(outbox)
        .insert(any());

    assertThatThrownBy(() -> service.create("user-1", 1050L, "key-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Failure after event insert");

    var payment = repository.findByIdempotencyKey("key-1").orElseThrow();
    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.processorReference()).isNull();
    assertThat(payment.failureReason()).isNull();
    assertThat(payment.updatedAt()).isEqualTo(payment.createdAt());
    assertThat(eventCount()).isZero();
  }

  @Test
  void unknownIdReturnsEmpty() {
    assertThat(service.findById(UUID.randomUUID())).isEmpty();
  }

  private long eventCount() {
    return jdbcClient.sql("SELECT count(*) FROM payment_events").query(Long.class).single();
  }

  private void assertEvent(Payment payment, PaymentEvent.Type expectedType) {
    var event =
        jdbcClient
            .sql("SELECT * FROM payment_events WHERE payment_id = :paymentId")
            .param("paymentId", payment.id())
            .query(
                (rs, rowNum) -> {
                  var payload = jsonMapper.readValue(rs.getString("payload"), PaymentEvent.class);
                  assertThat(rs.getObject("id", UUID.class)).isEqualTo(payload.eventId());
                  assertThat(rs.getString("event_type")).isEqualTo(expectedType.name());
                  assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant())
                      .isEqualTo(payload.occurredAt());
                  assertThat(rs.getObject("processed_at")).isNull();
                  assertThat(rs.getInt("attempts")).isZero();
                  assertThat(rs.getString("last_error")).isNull();
                  return payload;
                })
            .single();
    assertThat(event.eventId()).isNotNull();
    assertThat(event.type()).isEqualTo(expectedType);
    assertThat(event.paymentId()).isEqualTo(payment.id());
    assertThat(event.userId()).isEqualTo(payment.userId());
    assertThat(event.amount()).isEqualTo(payment.amount());
    assertThat(event.occurredAt()).isEqualTo(payment.updatedAt());
    assertThat(eventCount()).isEqualTo(1L);
  }
}
