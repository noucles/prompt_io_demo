package com.nielsen.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
  private static final String PAYMENT_COLUMNS =
      "id, idempotency_key, user_id, amount, status, created_at, updated_at, processor_reference, failure_reason";

  private final JdbcClient jdbcClient;

  public PaymentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public boolean insertIfAbsent(Payment payment) {
    var result =
        jdbcClient
            .sql(
                """
                INSERT INTO payments (%s)
                VALUES (:id, :idempotencyKey, :userId, :amount, :status, :createdAt, :updatedAt,
                        :processorReference, :failureReason)
                ON CONFLICT (idempotency_key) DO NOTHING
                """
                    .formatted(PAYMENT_COLUMNS))
            .param("id", payment.id())
            .param("userId", payment.userId())
            .param("idempotencyKey", payment.idempotencyKey())
            .param("amount", payment.amount())
            .param("status", payment.status().name())
            .param("createdAt", toDb(payment.createdAt()))
            .param("updatedAt", toDb(payment.updatedAt()))
            .param("processorReference", payment.processorReference(), Types.VARCHAR)
            .param("failureReason", payment.failureReason(), Types.VARCHAR)
            .update();

    return result == 1;
  }

  public Optional<Payment> findById(UUID id) {
    return jdbcClient
        .sql(
            """
            SELECT %s
            FROM payments
            WHERE id = :id
            """
                .formatted(PAYMENT_COLUMNS))
        .param("id", id)
        .query(this::mapRow)
        .optional();
  }

  public boolean completeIfPending(
      UUID id,
      PaymentStatus status,
      String processorReference,
      String failureReason,
      Instant updatedAt) {
    return jdbcClient
            .sql(
                """
                UPDATE payments
                SET status = :status, processor_reference = :processorReference,
                    failure_reason = :failureReason, updated_at = :updatedAt
                WHERE id = :id AND status = 'PENDING'
                """)
            .param("id", id)
            .param("status", status.name())
            .param("processorReference", processorReference, Types.VARCHAR)
            .param("failureReason", failureReason, Types.VARCHAR)
            .param("updatedAt", toDb(updatedAt))
            .update()
        == 1;
  }

  public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
    return jdbcClient
        .sql(
            """
            SELECT %s
            FROM payments
            WHERE idempotency_key = :idempotencyKey
            """
                .formatted(PAYMENT_COLUMNS))
        .param("idempotencyKey", idempotencyKey)
        .query(this::mapRow)
        .optional();
  }

  private Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Payment(
        rs.getObject("id", UUID.class),
        rs.getString("user_id"),
        rs.getLong("amount"),
        PaymentStatus.valueOf(rs.getString("status")),
        instantFrom(rs, "created_at"),
        rs.getString("idempotency_key"),
        instantFrom(rs, "updated_at"),
        rs.getString("processor_reference"),
        rs.getString("failure_reason"));
  }

  private static OffsetDateTime toDb(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instantFrom(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
