package com.challenge.balance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;


public record TransactionRecord(
        String transactionId,
        OperationType type,
        String primaryAccountId,
        String secondaryAccountId,
        BigDecimal amount,
        Instant processedAt
) {

    public static TransactionRecord credit(
            String txId,
            String accountId,
            BigDecimal amount
    ) {
        return new TransactionRecord(txId, OperationType.CREDIT, accountId, null, amount, Instant.now());
    }

    public static TransactionRecord debit(
            String txId,
            String accountId,
            BigDecimal amount
    ) {
        return new TransactionRecord(txId, OperationType.DEBIT, accountId, null, amount, Instant.now());
    }

    public static TransactionRecord transfer(
            String txId,
            String sourceId,
            String destId,
            BigDecimal amount
    ) {
        return new TransactionRecord(txId, OperationType.TRANSFER, sourceId, destId, amount, Instant.now());
    }


    public boolean matches(
            OperationType type,
            String primaryAccountId,
            String secondaryAccountId,
            BigDecimal amount
    ) {
        return this.type == type
                && this.primaryAccountId.equals(primaryAccountId)
                && Objects.equals(this.secondaryAccountId, secondaryAccountId)
                && this.amount.compareTo(amount) == 0;
    }
}
