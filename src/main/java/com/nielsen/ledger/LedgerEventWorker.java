package com.nielsen.ledger;

import com.nielsen.outbox.OutboxRepository;
import com.nielsen.outbox.PaymentEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LedgerEventWorker {
  private static final Logger log = LoggerFactory.getLogger(LedgerEventWorker.class);

  public enum ProcessResult {
    NONE,
    PROCESSED,
    FAILED
  }

  private final OutboxRepository outboxRepository;
  private final LedgerService ledgerService;
  private final TransactionTemplate transactionTemplate;
  private final JsonMapper jsonMapper;
  private final int maxAttempts;

  public LedgerEventWorker(
      OutboxRepository outboxRepository,
      LedgerService ledgerService,
      TransactionTemplate transactionTemplate,
      JsonMapper jsonMapper,
      @Value("${outbox.worker.max-attempts:5}") int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("outbox.worker.max-attempts must be positive");
    }
    this.outboxRepository = outboxRepository;
    this.ledgerService = ledgerService;
    this.transactionTemplate = transactionTemplate;
    this.jsonMapper = jsonMapper;
    this.maxAttempts = maxAttempts;
  }

  /** Invoke without an outer transaction so each event commits independently. */
  public ProcessResult processNext() {
    var claimedId = new AtomicReference<UUID>();
    try {
      return Objects.requireNonNull(
          transactionTemplate.execute(
              transaction -> {
                var claimed = outboxRepository.claimNext(maxAttempts);
                if (claimed.isEmpty()) {
                  return ProcessResult.NONE;
                }
                var row = claimed.get();
                claimedId.set(row.eventId());
                var event = jsonMapper.readValue(row.payload(), PaymentEvent.class);
                ledgerService.apply(event);
                outboxRepository.markProcessed(
                    row.eventId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
                return ProcessResult.PROCESSED;
              }));
    } catch (RuntimeException failure) {
      UUID eventId = claimedId.get();
      if (eventId == null) {
        throw failure;
      }
      log.warn("Failed to process payment event {}", eventId, failure);
      // execute has rolled back before control reaches this separate, auto-committed statement.
      String error = failure.getClass().getSimpleName();
      if (failure.getMessage() != null) {
        error += ": " + failure.getMessage();
      }
      int attempts = outboxRepository.recordFailure(eventId, error);
      if (attempts >= maxAttempts) {
        log.error("Payment event {} parked after {} attempts", eventId, attempts);
      }
      return ProcessResult.FAILED;
    }
  }

  public int processBatch(int max) {
    int processed = 0;
    while (processed < max) {
      if (processNext() != ProcessResult.PROCESSED) {
        break;
      }
      processed++;
    }
    return processed;
  }

  @Scheduled(fixedDelayString = "${outbox.worker.poll-interval:1s}")
  public void poll() {
    processBatch(100);
  }
}
