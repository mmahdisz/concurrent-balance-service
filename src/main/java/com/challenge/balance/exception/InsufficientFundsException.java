package com.challenge.balance.exception;

public class InsufficientFundsException extends BalanceServiceException {
    public InsufficientFundsException(String accountId, long requested, long available) {
        super("Account " + accountId + " has insufficient funds: requested " + requested
                + ", available " + available);
    }
}
