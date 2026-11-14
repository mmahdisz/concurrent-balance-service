package com.challenge.balance.exception;

import java.math.BigDecimal;
public class InsufficientFundsException extends BalanceServiceException {
    public InsufficientFundsException(String accountId, BigDecimal requested, BigDecimal available) {
        super("Account " + accountId + " has insufficient funds: requested " + requested
                + ", available " + available);
    }
}
