package com.challenge.balance.exception;

public abstract class BalanceServiceException extends RuntimeException {
    protected BalanceServiceException(String message) {
        super(message);
    }
}
