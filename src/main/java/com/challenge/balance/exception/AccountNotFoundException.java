package com.challenge.balance.exception;

public class AccountNotFoundException extends BalanceServiceException {
    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
