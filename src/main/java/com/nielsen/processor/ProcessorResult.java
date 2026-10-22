package com.nielsen.processor;

public sealed interface ProcessorResult {
  record Approved(String processorReference) implements ProcessorResult {}

  record Declined(String reason) implements ProcessorResult {}
}
