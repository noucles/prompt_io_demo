package com.nielsen.outbox;

import java.util.UUID;

public record ClaimedEvent(UUID eventId, PaymentEvent event) {}
