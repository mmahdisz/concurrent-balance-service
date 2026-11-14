package com.challenge.balance.events;

import java.math.BigDecimal;
import com.challenge.balance.domain.OperationType;
import java.time.Instant;

/** Versioned integration contract. Amounts are exact decimal values in the API monetary unit. */
public record BalanceChangedEvent(int schemaVersion, String eventId, String transactionId,
                                  OperationType operation, String accountId, String counterpartyAccountId,
                                  BigDecimal delta, BigDecimal balance, Instant occurredAt) {
}
