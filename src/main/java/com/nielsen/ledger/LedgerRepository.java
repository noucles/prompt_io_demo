package com.nielsen.ledger;

import java.time.ZoneOffset;
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
}
