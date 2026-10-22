package com.nielsen.ledger;

import com.nielsen.outbox.PaymentEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {
  private final LedgerRepository repository;

  public LedgerService(LedgerRepository repository) {
    this.repository = repository;
  }

  public boolean apply(PaymentEvent event) {
    return switch (event.type()) {
      case PAYMENT_SUCCEEDED ->
          repository.insertIfAbsent(
              new LedgerEntry(
                  UUID.randomUUID(),
                  event.paymentId(),
                  event.eventId(),
                  event.userId(),
                  event.amount(),
                  Instant.now().truncatedTo(ChronoUnit.MICROS)));
      case PAYMENT_FAILED -> false;
    };
  }
}
