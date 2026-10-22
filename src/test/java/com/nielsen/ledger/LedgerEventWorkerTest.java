package com.nielsen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.ledger.LedgerEventWorker.ProcessResult;
import com.nielsen.outbox.OutboxRepository;
import com.nielsen.outbox.PaymentEvent;
import com.nielsen.payment.PaymentRepository;
import com.nielsen.payment.PaymentService;
import com.nielsen.processor.SimulatedPaymentProcessor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@JdbcTest(properties = "outbox.worker.max-attempts=3")
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
  TestcontainersConfiguration.class,
  OutboxRepository.class,
  LedgerRepository.class,
  LedgerService.class,
  LedgerEventWorker.class,
  PaymentRepository.class,
  PaymentService.class,
  SimulatedPaymentProcessor.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerEventWorkerTest {
  @Autowired PaymentService paymentService;
  @Autowired LedgerEventWorker worker;
  @Autowired JdbcClient jdbcClient;
  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate transactionTemplate;
  @MockitoSpyBean LedgerService ledgerService;
  @MockitoSpyBean OutboxRepository outbox;

  @Value("${outbox.worker.max-attempts}")
  int maxAttempts;

  @BeforeEach
  @AfterEach
  void cleanTables() {
    jdbcClient.sql("DELETE FROM ledger_entries").update();
    jdbcClient.sql("DELETE FROM payment_events").update();
    jdbcClient.sql("DELETE FROM payments").update();
  }

  @Test
  void processesPersistedEventAfterRestart() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    var event = eventFor(payment.id());
    assertThat(ledgerEntries()).isEmpty();
    assertThat(processedCount()).isZero();

    // Restart recovery: the committed event waits in the database until a worker runs.
    assertThat(worker.processBatch(10)).isEqualTo(1);

    assertThat(ledgerEntries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.paymentId()).isEqualTo(payment.id());
              assertThat(entry.eventId()).isEqualTo(event.eventId());
              assertThat(entry.userId()).isEqualTo(payment.userId());
              assertThat(entry.amount()).isEqualTo(payment.amount());
            });
    assertProcessed(payment.id());
  }

  @Test
  void failedPaymentIsProcessedWithoutLedgerEntry() {
    var payment = paymentService.create("user-1", 1001L, "key-1");

    assertThat(worker.processBatch(10)).isEqualTo(1);

    assertThat(ledgerEntries()).isEmpty();
    assertProcessed(payment.id());
  }

  @Test
  void alreadyProcessedEventIsNotProcessedAgain() {
    paymentService.create("user-1", 1050L, "key-1");
    assertThat(worker.processBatch(10)).isEqualTo(1);
    var original = ledgerEntries();

    assertThat(worker.processBatch(10)).isZero();

    assertThat(ledgerEntries()).isEqualTo(original);
    assertThat(processedCount()).isEqualTo(1L);
  }

  @Test
  void redeliveryMarksEventWithoutDuplicatingLedgerEntry() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    assertThat(ledgerService.apply(eventFor(payment.id()))).isTrue();
    var original = ledgerEntries();

    assertThat(worker.processBatch(10)).isEqualTo(1);

    assertThat(ledgerEntries()).hasSize(1).isEqualTo(original);
    assertProcessed(payment.id());
  }

  @Test
  void emptyQueueReturnsNoneAndZero() {
    assertThat(worker.processNext()).isEqualTo(ProcessResult.NONE);
    assertThat(worker.processBatch(10)).isZero();
  }

  @Test
  void batchStopsAtItsLimit() {
    for (int i = 0; i < 3; i++) {
      paymentService.create("user-1", 1050L, "key-" + i);
    }

    assertThat(worker.processBatch(0)).isZero();
    assertThat(processedCount()).isZero();
    assertThat(worker.processBatch(2)).isEqualTo(2);
    assertThat(processedCount()).isEqualTo(2L);
    assertThat(worker.processBatch(10)).isEqualTo(1);
    assertThat(ledgerEntries()).hasSize(3);
    assertThat(processedCount()).isEqualTo(3L);
  }

  @Test
  void claimsOldestEventFirst() {
    var newer = paymentService.create("user-1", 1050L, "key-1");
    var older = paymentService.create("user-1", 1050L, "key-2");
    makeOldest(older.id());

    assertThat(worker.processNext()).isEqualTo(ProcessResult.PROCESSED);

    assertProcessed(older.id());
    assertThat(processedCount()).isEqualTo(1L);
    assertThat(ledgerEntries()).extracting(LedgerEntry::paymentId).containsExactly(older.id());
    assertThat(eventFor(newer.id())).isNotNull();
  }

  @Test
  void applyFailureLeavesEventUnprocessed() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    var failure = new IllegalStateException("Ledger unavailable");
    doThrow(failure).when(ledgerService).apply(any());

    assertThat(worker.processNext()).isEqualTo(ProcessResult.FAILED);

    assertThat(ledgerEntries()).isEmpty();
    assertThat(processedCount()).isZero();
    verify(outbox, never()).markProcessed(any(), any());
    assertFailure(payment.id(), 1);
    assertThat(retryState(payment.id()).lastError()).contains("Ledger unavailable");
  }

  @Test
  void markProcessedFailureRollsBackLedgerInsert() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    var failure = new DataAccessResourceFailureException("Cannot mark event processed");
    doThrow(failure).when(outbox).markProcessed(any(), any());

    assertThat(worker.processNext()).isEqualTo(ProcessResult.FAILED);

    assertThat(ledgerEntries()).isEmpty();
    assertThat(processedCount()).isZero();
    assertFailure(payment.id(), 1);
    doCallRealMethod().when(outbox).markProcessed(any(), any());
    assertThat(worker.processNext()).isEqualTo(ProcessResult.PROCESSED);
    assertThat(ledgerEntries()).hasSize(1);
    assertProcessed(payment.id());
  }

  @Test
  void laterFailureDoesNotRollBackEarlierEventInBatch() {
    var first = paymentService.create("user-1", 1050L, "key-1");
    var second = paymentService.create("user-1", 1050L, "key-2");
    makeOldest(first.id());
    UUID secondEventId = eventFor(second.id()).eventId();
    var failure = new DataAccessResourceFailureException("Second event failed");
    doThrow(failure).when(outbox).markProcessed(eq(secondEventId), any());

    assertThat(worker.processBatch(10)).isEqualTo(1);

    assertThat(ledgerEntries()).extracting(LedgerEntry::paymentId).containsExactly(first.id());
    assertProcessed(first.id());
    assertThat(processedCount()).isEqualTo(1L);
    assertFailure(second.id(), 1);
    doCallRealMethod().when(outbox).markProcessed(eq(secondEventId), any());
    assertThat(worker.processBatch(10)).isEqualTo(1);
    assertThat(ledgerEntries()).hasSize(2);
  }

  @Test
  void anotherWorkerSkipsLockedEvent() {
    paymentService.create("user-1", 1050L, "key-1");
    paymentService.create("user-1", 1050L, "key-2");

    try (var executor = Executors.newSingleThreadExecutor()) {
      transactionTemplate.executeWithoutResult(
          transaction -> {
            var locked = outbox.claimNext(maxAttempts).orElseThrow();
            var otherWorker = executor.submit(worker::processNext);
            assertThatCode(
                    () ->
                        assertThat(otherWorker.get(10, TimeUnit.SECONDS))
                            .isEqualTo(ProcessResult.PROCESSED))
                .doesNotThrowAnyException();
            assertThat(ledgerEntries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.eventId()).isNotEqualTo(locked.eventId()));
            assertThat(processedCount()).isEqualTo(1L);
          });
    }

    assertThat(worker.processNext()).isEqualTo(ProcessResult.PROCESSED);
    assertThat(worker.processNext()).isEqualTo(ProcessResult.NONE);
    assertThat(ledgerEntries()).hasSize(2);
    assertThat(processedCount()).isEqualTo(2L);
  }

  @Test
  void pollProcessesAvailableEvents() {
    var payment = paymentService.create("user-1", 1050L, "key-1");

    worker.poll();

    assertThat(ledgerEntries()).hasSize(1);
    assertProcessed(payment.id());
  }

  @Test
  void retriesOnLaterBatchAndRecovers() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    var event = eventFor(payment.id());
    doThrow(new IllegalStateException("Temporary ledger failure"))
        .doCallRealMethod()
        .when(ledgerService)
        .apply(any());

    assertThat(worker.processBatch(10)).isZero();

    assertFailure(payment.id(), 1);
    assertThat(retryState(payment.id()).lastError()).contains("Temporary ledger failure");
    assertThat(ledgerEntries()).isEmpty();
    verify(ledgerService, times(1)).apply(event);

    assertThat(worker.processBatch(10)).isEqualTo(1);

    assertProcessed(payment.id());
    assertThat(ledgerEntries()).hasSize(1);
    assertThat(retryState(payment.id()).attempts()).isEqualTo(1);
    verify(ledgerService, times(2)).apply(event);
  }

  @Test
  void parksEventAtConfiguredAttemptLimit() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    var event = eventFor(payment.id());
    doThrow(new IllegalStateException("Persistent ledger failure"))
        .when(ledgerService)
        .apply(any());

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      assertThat(worker.processBatch(10)).isZero();
      assertFailure(payment.id(), attempt);
    }

    assertThat(worker.processBatch(10)).isZero();
    assertThat(worker.processNext()).isEqualTo(ProcessResult.NONE);
    assertFailure(payment.id(), maxAttempts);
    assertThat(ledgerEntries()).isEmpty();
    verify(ledgerService, times(maxAttempts)).apply(event);
  }

  @Test
  void freshEventPassesOlderFailingEventOnNextPoll() {
    var failing = paymentService.create("user-1", 1050L, "key-1");
    var healthy = paymentService.create("user-1", 1050L, "key-2");
    makeOldest(failing.id());
    var badEvent = eventFor(failing.id());
    doThrow(new IllegalStateException("Only this event fails")).when(ledgerService).apply(badEvent);

    assertThat(worker.processBatch(10)).isZero();
    assertFailure(failing.id(), 1);
    assertThat(ledgerEntries()).isEmpty();

    assertThat(worker.processBatch(10)).isEqualTo(1);

    assertProcessed(healthy.id());
    assertFailure(failing.id(), 2);
    assertThat(ledgerEntries()).extracting(LedgerEntry::paymentId).containsExactly(healthy.id());
    verify(ledgerService, times(2)).apply(badEvent);
  }

  @Test
  void malformedPayloadIsCountedAndParkedUsingRowId() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    UUID eventId = eventFor(payment.id()).eventId();
    jdbcClient
        .sql("UPDATE payment_events SET payload = '{\"bogus\": true}' WHERE id = :id")
        .param("id", eventId)
        .update();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      assertThat(worker.processBatch(10)).isZero();
      assertFailure(payment.id(), attempt);
    }

    assertThat(worker.processBatch(10)).isZero();
    assertThat(worker.processNext()).isEqualTo(ProcessResult.NONE);
    assertThat(ledgerEntries()).isEmpty();
    verify(outbox, times(maxAttempts)).recordFailure(eq(eventId), any());
    verify(outbox, never()).markProcessed(any(), any());
  }

  @Test
  void truncatesStoredErrorMessage() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    doThrow(new IllegalStateException("x".repeat(2000))).when(ledgerService).apply(any());

    assertThat(worker.processNext()).isEqualTo(ProcessResult.FAILED);

    assertFailure(payment.id(), 1);
    assertThat(retryState(payment.id()).lastError())
        .hasSize(1000)
        .startsWith("IllegalStateException: ");
  }

  @Test
  void pollDoesNotThrowForEventFailure() {
    var payment = paymentService.create("user-1", 1050L, "key-1");
    doThrow(new IllegalStateException("Ledger unavailable")).when(ledgerService).apply(any());

    assertThatCode(worker::poll).doesNotThrowAnyException();

    assertFailure(payment.id(), 1);
  }

  @Test
  void claimFailurePropagatesWithoutAttributingItToAnEvent() {
    var failure = new DataAccessResourceFailureException("Database unavailable");
    doThrow(failure).when(outbox).claimNext(maxAttempts);

    assertThatThrownBy(worker::processNext).isSameAs(failure);

    verify(outbox, never()).recordFailure(any(), any());
  }

  private record RetryState(int attempts, String lastError, OffsetDateTime processedAt) {}

  private RetryState retryState(UUID paymentId) {
    return jdbcClient
        .sql("SELECT attempts, last_error, processed_at FROM payment_events WHERE payment_id = :id")
        .param("id", paymentId)
        .query(
            (rs, rowNum) ->
                new RetryState(
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    rs.getObject("processed_at", OffsetDateTime.class)))
        .single();
  }

  private void assertFailure(UUID paymentId, int attempts) {
    var state = retryState(paymentId);
    assertThat(state.attempts()).isEqualTo(attempts);
    assertThat(state.lastError()).isNotBlank();
    assertThat(state.processedAt()).isNull();
  }

  private PaymentEvent eventFor(UUID paymentId) {
    String payload =
        jdbcClient
            .sql("SELECT payload::text FROM payment_events WHERE payment_id = :paymentId")
            .param("paymentId", paymentId)
            .query(String.class)
            .single();
    return jsonMapper.readValue(payload, PaymentEvent.class);
  }

  private List<LedgerEntry> ledgerEntries() {
    return jdbcClient
        .sql("SELECT id, payment_id, event_id, user_id, amount, created_at FROM ledger_entries")
        .query(
            (rs, rowNum) ->
                new LedgerEntry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("user_id"),
                    rs.getLong("amount"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  private long processedCount() {
    return jdbcClient
        .sql("SELECT count(*) FROM payment_events WHERE processed_at IS NOT NULL")
        .query(Long.class)
        .single();
  }

  private void assertProcessed(UUID paymentId) {
    assertThat(
            jdbcClient
                .sql(
                    """
                    SELECT processed_at FROM payment_events WHERE payment_id = :paymentId
                    """)
                .param("paymentId", paymentId)
                .query((rs, rowNum) -> rs.getObject("processed_at", OffsetDateTime.class))
                .single())
        .isNotNull();
  }

  private void makeOldest(UUID paymentId) {
    jdbcClient
        .sql(
            """
            UPDATE payment_events SET created_at = '2000-01-01T00:00:00Z'
            WHERE payment_id = :paymentId
            """)
        .param("paymentId", paymentId)
        .update();
  }
}
