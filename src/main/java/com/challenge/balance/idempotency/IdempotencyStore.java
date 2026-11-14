package com.challenge.balance.idempotency;

import com.challenge.balance.domain.TransactionRecord;

import java.util.Optional;

/** Completed operations. Durable implementations must join the balance transaction. */
public interface IdempotencyStore {

    Optional<TransactionRecord> find(String transactionId);

    /** Insert a new record; never replace the receipt for an existing transaction ID. */
    void record(TransactionRecord record);
}
