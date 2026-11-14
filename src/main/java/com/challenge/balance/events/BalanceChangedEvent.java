package com.challenge.balance.events;

import com.challenge.balance.domain.OperationType;
import java.time.Instant;

/** Versioned integration contract. Amounts are signed integer minor units. */
public record BalanceChangedEvent(int schemaVersion, String eventId, String transactionId,
                                  OperationType operation, String accountId, String counterpartyAccountId,
                                  long delta, long balance, Instant occurredAt) {
}
