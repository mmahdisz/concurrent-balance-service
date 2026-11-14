package com.challenge.balance.events;

import com.challenge.balance.domain.Account;
import com.challenge.balance.domain.TransactionRecord;

@FunctionalInterface
public interface BalanceEventRecorder {
    void record(TransactionRecord transaction, Account primary, Account secondary);
}
