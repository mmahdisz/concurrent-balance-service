package com.challenge.balance.exception;

public class InvalidAmountException extends BalanceServiceException {
    public InvalidAmountException(long amount) {
        super("Amount must be positive, got: " + amount);
    }
}
