package com.challenge.balance.web.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AmountRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 255) String transactionId
) {
}
