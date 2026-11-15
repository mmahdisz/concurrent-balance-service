package com.challenge.balance.web.dto;

import java.math.BigDecimal;
import com.challenge.balance.web.validation.ValidAccountId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateAccountRequest(
        @ValidAccountId String accountId,
        @NotNull @PositiveOrZero BigDecimal initialBalance
) {
}
