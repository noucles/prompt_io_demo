package com.nielsen.processor;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SimulatedPaymentProcessor implements PaymentProcessor {
  @Override
  public ProcessorResult charge(UUID paymentId, String userId, long amount) {
    return switch ((int) (amount % 100)) {
      case 1 -> new ProcessorResult.Declined("insufficient_funds");
      case 2 -> throw new ProcessorUnavailableException("Simulated payment processor unavailable");
      default -> new ProcessorResult.Approved("sim_" + paymentId);
    };
  }
}
