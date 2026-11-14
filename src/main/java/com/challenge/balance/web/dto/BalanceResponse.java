package com.challenge.balance.web.dto;

import java.math.BigDecimal;
public record BalanceResponse(String accountId, BigDecimal balance) {
}
