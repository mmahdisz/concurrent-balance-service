package com.challenge.balance.idempotency;

import java.math.BigDecimal;
import com.challenge.balance.domain.OperationType;
import com.challenge.balance.domain.TransactionRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TransactionRecord> find(String transactionId) {
        return jdbc.query("SELECT * FROM processed_transactions WHERE transaction_id = ?",
                        (rs, row) -> new TransactionRecord(
                                rs.getString("transaction_id"),
                                OperationType.valueOf(rs.getString("operation_type")),
                                rs.getString("primary_account_id"),
                                rs.getString("secondary_account_id"),
                                rs.getBigDecimal("amount"),
                                rs.getObject("processed_at", OffsetDateTime.class).toInstant()), transactionId)
                .stream().findFirst();
    }

    @Override
    public void record(TransactionRecord record) {
        jdbc.update("""
                INSERT INTO processed_transactions
                    (transaction_id, operation_type, primary_account_id, secondary_account_id, amount, processed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, record.transactionId(), record.type().name(), record.primaryAccountId(),
                record.secondaryAccountId(), record.amount(), record.processedAt().atOffset(ZoneOffset.UTC));
    }
}
