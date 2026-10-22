package com.nielsen.ledger;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
  private final JdbcClient jdbcClient;

  public LedgerRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public boolean insertIfAbsent(LedgerEntry entry) {
    return jdbcClient
            .sql(
                """
                INSERT INTO ledger_entries (id, payment_id, event_id, user_id, amount, created_at)
                VALUES (:id, :paymentId, :eventId, :userId, :amount, :createdAt)
                ON CONFLICT DO NOTHING
                """)
            .param("id", entry.id())
            .param("paymentId", entry.paymentId())
            .param("eventId", entry.eventId())
            .param("userId", entry.userId())
            .param("amount", entry.amount())
            .param("createdAt", entry.createdAt().atOffset(ZoneOffset.UTC))
            .update()
        == 1;
  }

  public List<LedgerEntry> findByPaymentId(UUID paymentId) {
    return jdbcClient
        .sql(
            """
            SELECT id, payment_id, event_id, user_id, amount, created_at
            FROM ledger_entries
            WHERE payment_id = :paymentId
            ORDER BY created_at, id
            """)
        .param("paymentId", paymentId)
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
}
