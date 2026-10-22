package com.nielsen.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
    UUID id, UUID paymentId, UUID eventId, String userId, long amount, Instant createdAt) {}
