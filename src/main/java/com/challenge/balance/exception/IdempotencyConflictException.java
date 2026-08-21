package com.challenge.balance.exception;


public class IdempotencyConflictException extends BalanceServiceException {
    public IdempotencyConflictException(String transactionId) {
        super("transactionId " + transactionId + " was already used for a different operation");
    }
}
