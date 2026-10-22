package com.nielsen.outbox;

import java.util.UUID;

public record ClaimedEvent(UUID eventId, String payload) {}
