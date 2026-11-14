package com.challenge.balance.domain;

import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import java.math.BigDecimal;

public final class Account {
    private final String id;
    private volatile BigDecimal balance;

    public Account(String id, BigDecimal initialBalance) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Account id must not be blank");
        }
        if (initialBalance == null || initialBalance.signum() < 0) {
            throw new IllegalArgumentException("Initial balance must not be null or negative");
        }
        this.id = id;
        this.balance = initialBalance;
    }

    public String getId() { return id; }

    /** Safe to call without holding the account lock. BigDecimal is immutable. */
    public BigDecimal getBalance() { return balance; }

    /** Caller must hold this account's lock. */
    public void credit(BigDecimal amount) {
        validateAmount(amount);
        balance = balance.add(amount);
    }

    /** Caller must hold this account's lock. */
    public void debit(BigDecimal amount) {
        validateAmount(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, amount, balance);
        }
        balance = balance.subtract(amount);
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException(amount);
        }
    }
}
