package com.nielsen.repository;

import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
  private static final String ID_COLUMN = "id";
  private static final String IDEMPOTENCY_KEY_COLUMN = "idempotency_key";
  private static final String USER_ID_COLUMN = "user_id";
  private static final String AMOUNT_COLUMN = "amount";
  private static final String STATUS_COLUMN = "status";
  private static final String CREATED_AT_COLUMN = "created_at";
  private static final String UPDATED_AT_COLUMN = "updated_at";

  private static final String PAYMENT_COLUMNS =
      String.join(
          ", ",
          ID_COLUMN,
          IDEMPOTENCY_KEY_COLUMN,
          USER_ID_COLUMN,
          AMOUNT_COLUMN,
          STATUS_COLUMN,
          CREATED_AT_COLUMN,
          UPDATED_AT_COLUMN);
  private static final String SELECT_PAYMENTS = "SELECT " + PAYMENT_COLUMNS + " FROM payments";

  private final JdbcClient jdbcClient;

  public PaymentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public boolean insertIfAbsent(Payment payment) {
    var result =
        jdbcClient
            .sql(
                "INSERT INTO payments ("
                    + PAYMENT_COLUMNS
                    + ") "
                    + "VALUES (:id, :idempotencyKey, :userId, :amount, :status, :createdAt, :updatedAt) "
                    + "ON CONFLICT ("
                    + IDEMPOTENCY_KEY_COLUMN
                    + ") DO NOTHING")
            .param("id", payment.id())
            .param("userId", payment.userId())
            .param("idempotencyKey", payment.idempotencyKey())
            .param("amount", payment.amount())
            .param("status", payment.status().name())
            .param("createdAt", toDb(payment.createdAt()))
            .param("updatedAt", toDb(payment.updatedAt()))
            .update();

    return result == 1;
  }

  public Optional<Payment> findById(UUID id) {
    return jdbcClient
        .sql(SELECT_PAYMENTS + " WHERE " + ID_COLUMN + " = :id")
        .param("id", id)
        .query(this::mapRow)
        .optional();
  }

  public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
    return jdbcClient
        .sql(SELECT_PAYMENTS + " WHERE " + IDEMPOTENCY_KEY_COLUMN + " = :idempotencyKey")
        .param("idempotencyKey", idempotencyKey)
        .query(this::mapRow)
        .optional();
  }

  private Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Payment(
        rs.getObject(ID_COLUMN, UUID.class),
        rs.getString(USER_ID_COLUMN),
        rs.getLong(AMOUNT_COLUMN),
        PaymentStatus.valueOf(rs.getString(STATUS_COLUMN)),
        instantFrom(rs, CREATED_AT_COLUMN),
        rs.getString(IDEMPOTENCY_KEY_COLUMN),
        instantFrom(rs, UPDATED_AT_COLUMN));
  }

  private static OffsetDateTime toDb(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instantFrom(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
