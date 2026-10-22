package com.nielsen.outbox;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OutboxRepository {
  private final JdbcClient jdbcClient;
  private final JsonMapper jsonMapper;

  public OutboxRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
    this.jdbcClient = jdbcClient;
    this.jsonMapper = jsonMapper;
  }

  public void insert(PaymentEvent event) {
    jdbcClient
        .sql(
            """
            INSERT INTO payment_events (id, payment_id, event_type, payload, created_at)
            VALUES (:id, :paymentId, :eventType, CAST(:payload AS jsonb), :createdAt)
            """)
        .param("id", event.eventId())
        .param("paymentId", event.paymentId())
        .param("eventType", event.type().name())
        .param("payload", jsonMapper.writeValueAsString(event))
        .param("createdAt", event.occurredAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  /** Must be called inside the transaction that applies and marks the event processed. */
  public Optional<ClaimedEvent> claimNext(int maxAttempts) {
    return jdbcClient
        .sql(
            """
            SELECT id, payload FROM payment_events
            WHERE processed_at IS NULL AND attempts < :maxAttempts
            ORDER BY attempts, created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
        .param("maxAttempts", maxAttempts)
        .query(
            (rs, rowNum) ->
                new ClaimedEvent(rs.getObject("id", UUID.class), rs.getString("payload")))
        .optional();
  }

  /**
   * Called after the processing transaction has rolled back, so the failure is committed
   * separately.
   */
  public int recordFailure(UUID eventId, String error) {
    return jdbcClient
        .sql(
            """
            UPDATE payment_events
            SET attempts = attempts + 1, last_error = LEFT(:error, 1000)
            WHERE id = :eventId
            RETURNING attempts
            """)
        .param("eventId", eventId)
        .param("error", error)
        .query(Integer.class)
        .single();
  }

  public void markProcessed(UUID eventId, Instant processedAt) {
    jdbcClient
        .sql(
            """
            UPDATE payment_events
            SET processed_at = :processedAt
            WHERE id = :eventId
            """)
        .param("eventId", eventId)
        .param("processedAt", processedAt.atOffset(ZoneOffset.UTC))
        .update();
  }
}
