package com.nielsen.domain;

import java.time.Instant;
import java.util.UUID;

public record Payment(
    UUID id,
    String userId,
    long amount,
    PaymentStatus status,
    Instant createdAt,
    String idempotencyKey) {}
