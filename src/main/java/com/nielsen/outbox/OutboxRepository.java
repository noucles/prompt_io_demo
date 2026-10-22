package com.nielsen.outbox;

import java.time.ZoneOffset;
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
}
