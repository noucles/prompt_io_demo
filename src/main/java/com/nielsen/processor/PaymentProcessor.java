package com.nielsen.processor;

import java.util.UUID;

public interface PaymentProcessor {
  /**
   * Charges a payment using {@code paymentId} as the provider-side idempotency key.
   *
   * @throws ProcessorUnavailableException if no definitive processor answer is available; the
   *     charge may or may not have happened
   */
  ProcessorResult charge(UUID paymentId, String userId, long amount);
}
