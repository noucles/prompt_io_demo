package com.nielsen.ledger;

import com.nielsen.outbox.OutboxRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LedgerEventWorker {
  private final OutboxRepository outboxRepository;
  private final LedgerService ledgerService;
  private final TransactionTemplate transactionTemplate;

  public LedgerEventWorker(
      OutboxRepository outboxRepository,
      LedgerService ledgerService,
      TransactionTemplate transactionTemplate) {
    this.outboxRepository = outboxRepository;
    this.ledgerService = ledgerService;
    this.transactionTemplate = transactionTemplate;
  }

  /** Invoke without an outer transaction so each event commits independently. */
  public boolean processNext() {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            transaction -> {
              var claimed = outboxRepository.claimNext();
              if (claimed.isEmpty()) {
                return false;
              }
              var event = claimed.get();
              ledgerService.apply(event.event());
              outboxRepository.markProcessed(
                  event.eventId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
              return true;
            }));
  }

  public int processBatch(int max) {
    int processed = 0;
    while (processed < max && processNext()) {
      processed++;
    }
    return processed;
  }

  @Scheduled(fixedDelayString = "${outbox.worker.poll-interval:1s}")
  public void poll() {
    processBatch(100);
  }
}
