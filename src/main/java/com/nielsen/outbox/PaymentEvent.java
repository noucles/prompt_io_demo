package com.nielsen.outbox;

import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(
    UUID eventId, Type type, UUID paymentId, String userId, long amount, Instant occurredAt) {
  public enum Type {
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED
  }
}
