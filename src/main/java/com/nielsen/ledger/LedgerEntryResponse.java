package com.nielsen.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
    UUID paymentId, UUID eventId, String userId, long amount, Instant createdAt) {
  public static LedgerEntryResponse from(LedgerEntry entry) {
    return new LedgerEntryResponse(
        entry.paymentId(), entry.eventId(), entry.userId(), entry.amount(), entry.createdAt());
  }
}
