package com.nielsen.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
    @NotBlank @Size(max = 64) String userId, @Positive long amount) {}
