package com.challenge.balance.idempotency;

import com.challenge.balance.domain.TransactionRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IdempotencyStore {

    private final ConcurrentHashMap<String, TransactionRecord> processed = new ConcurrentHashMap<>();

    public Optional<TransactionRecord> find(
            String transactionId
    ) {
        return Optional.ofNullable(processed.get(transactionId));
    }

    public void record(
            TransactionRecord record
    ) {
        processed.put(record.transactionId(), record);
    }
}
