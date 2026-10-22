package com.nielsen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nielsen.TestcontainersConfiguration;
import com.nielsen.outbox.PaymentEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import({TestcontainersConfiguration.class, LedgerRepository.class, LedgerService.class})
class LedgerServiceTest {
  @Autowired LedgerService service;
  @Autowired JdbcClient jdbcClient;

  @Test
  void succeededEventRecordsEntry() {
    var event = event(PaymentEvent.Type.PAYMENT_SUCCEEDED);
    Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

    assertThat(service.apply(event)).isTrue();

    assertThat(entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.id()).isNotNull();
              assertThat(entry.paymentId()).isEqualTo(event.paymentId());
              assertThat(entry.eventId()).isEqualTo(event.eventId());
              assertThat(entry.userId()).isEqualTo(event.userId());
              assertThat(entry.amount()).isEqualTo(event.amount());
              assertThat(entry.createdAt()).isBetween(before, Instant.now());
            });
  }

  @Test
  void sameEventIsNotRecordedTwice() {
    var event = event(PaymentEvent.Type.PAYMENT_SUCCEEDED);
    assertThat(service.apply(event)).isTrue();
    var original = entries();

    assertThat(service.apply(event)).isFalse();

    assertThat(original).hasSize(1);
    assertThat(entries()).isEqualTo(original);
  }

  @Test
  void differentEventForSamePaymentIsNotRecorded() {
    var first = event(PaymentEvent.Type.PAYMENT_SUCCEEDED);
    var second =
        new PaymentEvent(
            UUID.randomUUID(),
            first.type(),
            first.paymentId(),
            "user-2",
            2000L,
            first.occurredAt());
    assertThat(service.apply(first)).isTrue();
    var original = entries();

    assertThat(service.apply(second)).isFalse();

    assertThat(original).hasSize(1);
    assertThat(entries()).isEqualTo(original);
  }

  @Test
  void sameEventIdForDifferentPaymentIsNotRecorded() {
    var first = event(PaymentEvent.Type.PAYMENT_SUCCEEDED);
    var second =
        new PaymentEvent(
            first.eventId(), first.type(), UUID.randomUUID(), "user-2", 2000L, first.occurredAt());
    assertThat(service.apply(first)).isTrue();
    var original = entries();

    assertThat(service.apply(second)).isFalse();

    assertThat(original).hasSize(1);
    assertThat(entries()).isEqualTo(original);
  }

  @Test
  void distinctPaymentsAreBothRecorded() {
    var first = event(PaymentEvent.Type.PAYMENT_SUCCEEDED);
    var second = event(PaymentEvent.Type.PAYMENT_SUCCEEDED);

    assertThat(service.apply(first)).isTrue();
    assertThat(service.apply(second)).isTrue();

    assertThat(entries())
        .extracting(LedgerEntry::paymentId)
        .containsExactlyInAnyOrder(first.paymentId(), second.paymentId());
  }

  @Test
  void failedEventRecordsNothing() {
    assertThat(service.apply(event(PaymentEvent.Type.PAYMENT_FAILED))).isFalse();

    assertThat(entries()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void nonpositiveAmountsAreRejected(long amount) {
    var event =
        new PaymentEvent(
            UUID.randomUUID(),
            PaymentEvent.Type.PAYMENT_SUCCEEDED,
            UUID.randomUUID(),
            "user-1",
            amount,
            Instant.now());

    assertThatThrownBy(() -> service.apply(event))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private List<LedgerEntry> entries() {
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

  private static PaymentEvent event(PaymentEvent.Type type) {
    return new PaymentEvent(
        UUID.randomUUID(),
        type,
        UUID.randomUUID(),
        "user-1",
        1050L,
        Instant.parse("2026-10-21T12:00:00Z"));
  }
}
