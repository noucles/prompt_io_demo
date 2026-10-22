package com.nielsen.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulatedPaymentProcessorTest {
  private static final UUID PAYMENT_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private final SimulatedPaymentProcessor processor = new SimulatedPaymentProcessor();

  @ParameterizedTest
  @ValueSource(longs = {1050, 1000, 1003, 1099, 2147483650L})
  void approvesOtherAmounts(long amount) {
    var result = processor.charge(PAYMENT_ID, "user-1", amount);

    assertThat(result)
        .isInstanceOfSatisfying(
            ProcessorResult.Approved.class,
            approved -> assertThat(approved.processorReference()).isNotBlank().startsWith("sim_"));
  }

  @ParameterizedTest
  @ValueSource(longs = {1001, 1, 101, 2001, 2147483701L})
  void declinesAmountsEndingInOneCent(long amount) {
    assertThat(processor.charge(PAYMENT_ID, "user-1", amount))
        .isEqualTo(new ProcessorResult.Declined("insufficient_funds"));
  }

  @ParameterizedTest
  @ValueSource(longs = {1002, 2, 102, 2002, 2147483702L})
  void throwsForAmountsEndingInTwoCents(long amount) {
    assertThatThrownBy(() -> processor.charge(PAYMENT_ID, "user-1", amount))
        .isInstanceOf(ProcessorUnavailableException.class);
  }

  @Test
  void repeatedChargeReturnsSameApprovalReference() {
    var first = processor.charge(PAYMENT_ID, "user-1", 1050L);

    assertThat(processor.charge(PAYMENT_ID, "user-1", 1050L)).isEqualTo(first);
  }
}
