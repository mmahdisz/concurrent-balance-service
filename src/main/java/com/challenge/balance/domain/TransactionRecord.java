package com.challenge.balance.domain;

import java.time.Instant;
import java.util.Objects;


public record TransactionRecord(
        String transactionId,
        OperationType type,
        String primaryAccountId,
        String secondaryAccountId,
        long amount,
        Instant processedAt
) {

    public static TransactionRecord credit(
            String txId,
            String accountId,
            long amount
    ) {
        return new TransactionRecord(txId, OperationType.CREDIT, accountId, null, amount, Instant.now());
    }

    public static TransactionRecord debit(
            String txId,
            String accountId,
            long amount
    ) {
        return new TransactionRecord(txId, OperationType.DEBIT, accountId, null, amount, Instant.now());
    }

    public static TransactionRecord transfer(
            String txId,
            String sourceId,
            String destId,
            long amount
    ) {
        return new TransactionRecord(txId, OperationType.TRANSFER, sourceId, destId, amount, Instant.now());
    }


    public boolean matches(
            OperationType type,
            String primaryAccountId,
            String secondaryAccountId,
            long amount
    ) {
        return this.type == type
                && this.primaryAccountId.equals(primaryAccountId)
                && Objects.equals(this.secondaryAccountId, secondaryAccountId)
                && this.amount == amount;
    }
}
