package com.challenge.balance.idempotency;

import com.challenge.balance.domain.TransactionRecord;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Unit-test fixture; does not provide durable or transactional balance updates. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, TransactionRecord> processed = new ConcurrentHashMap<>();

    @Override
    public Optional<TransactionRecord> find(String transactionId) {
        return Optional.ofNullable(processed.get(transactionId));
    }

    @Override
    public void record(TransactionRecord record) {
        if (processed.putIfAbsent(record.transactionId(), record) != null) {
            throw new DuplicateKeyException("Transaction ID already recorded");
        }
    }
}
