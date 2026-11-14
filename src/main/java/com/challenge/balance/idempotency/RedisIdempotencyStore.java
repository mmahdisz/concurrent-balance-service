package com.challenge.balance.idempotency;

import com.challenge.balance.domain.OperationType;
import com.challenge.balance.domain.TransactionRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Redis caches committed receipts; JDBC remains the authority on cache misses. */
@Repository
@Primary
@ConditionalOnProperty(name = "idempotency.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private final JdbcIdempotencyStore durable;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final String keyPrefix;
    private final long failureBackoffNanos;
    private final AtomicLong retryAt = new AtomicLong();

    public RedisIdempotencyStore(JdbcIdempotencyStore durable, StringRedisTemplate redis,
                                ObjectMapper mapper,
                                @Value("${idempotency.redis.ttl:24h}") Duration ttl,
                                @Value("${idempotency.redis.key-prefix:balance-service:local:idempotency:v1:}") String keyPrefix,
                                @Value("${idempotency.redis.failure-backoff:5s}") Duration failureBackoff) {
        if (ttl == null || ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("Redis idempotency TTL must be at least one millisecond");
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Redis idempotency key prefix must not be blank");
        }
        if (failureBackoff == null || failureBackoff.isNegative() || failureBackoff.isZero()) {
            throw new IllegalArgumentException("Redis failure backoff must be positive");
        }
        this.durable = durable;
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        this.keyPrefix = keyPrefix;
        this.failureBackoffNanos = failureBackoff.toNanos();
    }

    @Override
    public Optional<TransactionRecord> find(String transactionId) {
        Optional<TransactionRecord> cached = readCache(transactionId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<TransactionRecord> record = durable.find(transactionId);
        record.ifPresent(this::cacheAfterCommit);
        return record;
    }

    @Override
    public void record(TransactionRecord record) {
        durable.record(record);
        cacheAfterCommit(record);
    }

    private Optional<TransactionRecord> readCache(String transactionId) {
        if (backingOff()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(transactionId));
            if (json == null) {
                return Optional.empty();
            }
            TransactionRecord record = mapper.readValue(json, TransactionRecord.class);
            if (validReceipt(record, transactionId)) {
                return Optional.of(record);
            }
            log.warn("Ignoring invalid Redis idempotency receipt; consulting JDBC");
        } catch (JsonProcessingException ex) {
            log.warn("Ignoring malformed Redis idempotency receipt; consulting JDBC");
        } catch (DataAccessException ex) {
            cacheUnavailable();
        }
        return Optional.empty();
    }

    private void cacheAfterCommit(TransactionRecord record) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A read can also see this transaction's own uncommitted insert.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        writeCache(record);
                    }
                });
            }
        } else {
            // JDBC has already committed an autocommit insert/read.
            writeCache(record);
        }
    }

    private void writeCache(TransactionRecord record) {
        if (backingOff()) {
            return;
        }
        try {
            // Value and expiry are set atomically. Cache failure cannot undo a DB commit.
            redis.opsForValue().set(key(record.transactionId()), mapper.writeValueAsString(record), ttl);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to encode Redis idempotency receipt; JDBC receipt remains available");
        } catch (DataAccessException ex) {
            cacheUnavailable();
        }
    }

    private boolean backingOff() {
        long deadline = retryAt.get();
        return deadline != 0 && System.nanoTime() - deadline < 0;
    }

    private void cacheUnavailable() {
        long now = System.nanoTime();
        long previous = retryAt.get();
        if ((previous == 0 || now - previous >= 0)
                && retryAt.compareAndSet(previous, now + failureBackoffNanos)) {
            log.warn("Redis idempotency cache unavailable; temporarily using JDBC");
        }
    }

    private static boolean validReceipt(TransactionRecord record, String transactionId) {
        return record != null && transactionId.equals(record.transactionId())
                && record.type() != null && record.processedAt() != null && record.amount() > 0
                && record.primaryAccountId() != null && !record.primaryAccountId().isBlank()
                && (record.type() == OperationType.TRANSFER
                    ? record.secondaryAccountId() != null && !record.secondaryAccountId().isBlank()
                        && !record.primaryAccountId().equals(record.secondaryAccountId())
                    : record.secondaryAccountId() == null);
    }

    private String key(String transactionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(transactionId.getBytes(StandardCharsets.UTF_8));
            return keyPrefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
