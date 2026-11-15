package com.challenge.balance.idempotency;

import java.math.BigDecimal;
import com.challenge.balance.domain.TransactionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyStoreTest {

    @Mock private JdbcIdempotencyStore durable;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final TransactionRecord receipt = TransactionRecord.credit("TX-1", "A", new BigDecimal("100.125"));
    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        store = new RedisIdempotencyStore(durable, redis, mapper, Duration.ofHours(24),
                "test:idempotency:v1:", Duration.ofSeconds(5));
    }

    @Test
    void cacheHitAvoidsDatabaseAndUsesHashedNamespacedKey() throws Exception {
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(receipt));

        assertThat(store.find("TX-1")).contains(receipt);

        verify(values).get(matches("test:idempotency:v1:[0-9a-f]{64}"));
        verifyNoInteractions(durable);
    }

    @Test
    void cacheMissLoadsDurableReceiptAndSetsExpiryAtomically() throws Exception {
        when(durable.find("TX-1")).thenReturn(Optional.of(receipt));

        assertThat(store.find("TX-1")).contains(receipt);

        verify(values).set(anyString(), eq(mapper.writeValueAsString(receipt)), eq(Duration.ofHours(24)));
    }

    @Test
    void absentDurableReceiptIsNeverNegativeCached() {
        when(durable.find("TX-1")).thenReturn(Optional.empty());

        assertThat(store.find("TX-1")).isEmpty();

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken-json", "null", "{}",
            "{\"transactionId\":\"other\",\"type\":\"CREDIT\",\"primaryAccountId\":\"A\",\"amount\":100,\"processedAt\":\"2026-01-01T00:00:00Z\"}"})
    void malformedIncompleteOrMiskeyedReceiptsFallBackToDatabase(String cached) {
        when(values.get(anyString())).thenReturn(cached);
        when(durable.find("TX-1")).thenReturn(Optional.of(receipt));

        assertThat(store.find("TX-1")).contains(receipt);
        verify(durable).find("TX-1");
    }

    @Test
    void unavailableRedisFallsBackAndBacksOffFurtherRequests() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        when(durable.find("TX-1")).thenReturn(Optional.of(receipt));

        assertThat(store.find("TX-1")).contains(receipt);
        assertThat(store.find("TX-1")).contains(receipt);

        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(durable, times(2)).find("TX-1");
    }

    @Test
    void redisWriteFailureDoesNotFailCommittedOperation() {
        doThrow(new RedisConnectionFailureException("offline"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        store.record(receipt);

        verify(durable).record(receipt);
    }

    @Test
    void duplicateDurableInsertIsNotCached() {
        doThrow(new DuplicateKeyException("duplicate")).when(durable).record(receipt);

        assertThatThrownBy(() -> store.record(receipt)).isInstanceOf(DuplicateKeyException.class);

        verifyNoInteractions(redis);
    }

    @Test
    void uncommittedInsertAndReadAreOnlyCachedAfterCommit() {
        when(durable.find("TX-1")).thenReturn(Optional.of(receipt));
        beginSynchronization();
        try {
            store.record(receipt);
            assertThat(store.find("TX-1")).contains(receipt);
            verify(values, never()).set(anyString(), anyString(), any(Duration.class));

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(values, times(2)).set(anyString(), anyString(), eq(Duration.ofHours(24)));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void rollbackDoesNotPublishReceipt() {
        beginSynchronization();
        try {
            store.record(receipt);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verifyNoInteractions(redis);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void invalidConfigurationFailsFast() {
        assertThatThrownBy(() -> new RedisIdempotencyStore(durable, redis, mapper,
                Duration.ZERO, "prefix:", Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisIdempotencyStore(durable, redis, mapper,
                Duration.ofHours(24), " ", Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisIdempotencyStore(durable, redis, mapper,
                Duration.ofHours(24), "prefix:", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private void beginSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
