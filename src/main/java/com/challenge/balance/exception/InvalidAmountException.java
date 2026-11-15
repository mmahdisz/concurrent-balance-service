package com.challenge.balance.exception;

import java.math.BigDecimal;
public class InvalidAmountException extends BalanceServiceException {
    public InvalidAmountException(BigDecimal amount) {
        super("Amount must be positive, got: " + amount);
    }
}
