package com.challenge.balance.exception;

public class AccountAlreadyExistsException extends BalanceServiceException {
    public AccountAlreadyExistsException(String accountId) {
        super("Account already exists: " + accountId);
    }
}
