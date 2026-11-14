package com.challenge.balance.events;

import java.math.BigDecimal;
import com.challenge.balance.domain.Account;
import com.challenge.balance.domain.OperationType;
import com.challenge.balance.domain.TransactionRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneOffset;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "balance.kafka", name = "enabled", havingValue = "true")
public class JdbcBalanceEventOutbox implements BalanceEventRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcBalanceEventOutbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void record(TransactionRecord transaction, Account primary, Account secondary) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox recording requires the balance database transaction");
        }
        BigDecimal delta = transaction.type() == OperationType.CREDIT ? transaction.amount() : transaction.amount().negate();
        append(transaction, primary, secondary == null ? null : secondary.getId(), delta);
        if (secondary != null) {
            append(transaction, secondary, primary.getId(), transaction.amount());
        }
    }

    private void append(TransactionRecord transaction, Account account, String counterparty, BigDecimal delta) {
        var event = new BalanceChangedEvent(1, UUID.randomUUID().toString(), transaction.transactionId(),
                transaction.type(), account.getId(), counterparty, delta, account.getBalance(), transaction.processedAt());
        try {
            jdbc.update("""
                    INSERT INTO kafka_outbox (event_id, account_id, payload, created_at)
                    VALUES (?, ?, ?, ?)
                    """, event.eventId(), event.accountId(), mapper.writeValueAsString(event),
                    event.occurredAt().atOffset(ZoneOffset.UTC));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize balance event", ex);
        }
    }
}
