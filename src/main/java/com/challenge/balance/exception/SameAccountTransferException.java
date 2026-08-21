package com.challenge.balance.exception;

public class SameAccountTransferException extends BalanceServiceException {
    public SameAccountTransferException(String accountId) {
        super("Source and destination account must differ, got: " + accountId);
    }
}
