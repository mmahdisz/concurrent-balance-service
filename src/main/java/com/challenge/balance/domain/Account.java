package com.challenge.balance.domain;

import java.util.concurrent.atomic.AtomicLong;


public final class Account {

    private final String id;
    private final AtomicLong balance;

    public Account(
            String id,
            long initialBalance
    ) {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Account id must not be blank");

        if (initialBalance < 0)
            throw new IllegalArgumentException("Initial balance must not be negative");

        this.id = id;
        this.balance = new AtomicLong(initialBalance);
    }

    public String getId() {
        return id;
    }

    /** Safe to call without holding the account lock. */
    public long getBalance() {
        return balance.get();
    }

    /** Caller MUST hold this account's lock. */
    public void credit(long amount) {
        balance.addAndGet(amount);
    }

    /** Caller MUST hold this account's lock and must have already verified sufficiency. */
    public void debit(long amount) {
        balance.addAndGet(-amount);
    }
}
