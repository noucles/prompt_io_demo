package com.nielsen.payment;

public class IdempotencyKeyConflictException extends RuntimeException {
  public IdempotencyKeyConflictException() {
    super("Idempotency key has already been used for a different payment request.");
  }
}
