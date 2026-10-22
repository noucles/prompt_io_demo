package com.nielsen.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.nielsen.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import({TestcontainersConfiguration.class, LedgerRepository.class})
class LedgerRepositoryTest {
  @Autowired LedgerRepository repository;

  @Test
  void findsOnlyRequestedPaymentsEntry() {
    var first = entry(1050L);
    var second = entry(2000L);
    repository.insertIfAbsent(first);
    repository.insertIfAbsent(second);

    assertThat(repository.findByPaymentId(first.paymentId())).containsExactly(first);
    assertThat(repository.findByPaymentId(second.paymentId())).containsExactly(second);
  }

  @Test
  void unknownPaymentReturnsEmptyList() {
    repository.insertIfAbsent(entry(1050L));

    assertThat(repository.findByPaymentId(UUID.randomUUID())).isEmpty();
  }

  private static LedgerEntry entry(long amount) {
    return new LedgerEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "user-1",
        amount,
        Instant.parse("2026-10-21T12:00:00.123456Z"));
  }
}
