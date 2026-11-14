package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.domain.TransactionRecord;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.idempotency.JdbcIdempotencyStore;
import com.challenge.balance.idempotency.RedisIdempotencyStore;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.repository.JdbcAccountRepository;
import com.challenge.balance.service.BalanceServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "idempotency.redis.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:redis-idempotency-tests;DB_CLOSE_DELAY=-1"
})
class RedisIdempotencyIntegrationTest {

    @Autowired private BalanceService service;
    @Autowired private IdempotencyStore store;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private StringRedisTemplate redis;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc.update("DELETE FROM processed_transactions");
        jdbc.update("DELETE FROM accounts");
        cache.clear();
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        doAnswer(call -> {
            cache.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        accounts.create("A", 1000);
        accounts.create("B", 200);
    }

    @Test
    void springSelectsRedisAndCacheLossStillPreventsDuplicateTransfers() {
        assertThat(store).isInstanceOf(RedisIdempotencyStore.class);
        service.transfer("A", "B", 300, "transfer");
        assertThat(cache).hasSize(1);

        service.transfer("A", "B", 300, "transfer");
        cache.clear(); // Expiration, eviction, or loss of Redis's entire dataset.
        independentService().transfer("A", "B", 300, "transfer");

        assertThat(service.getBalance("A")).isEqualTo(700);
        assertThat(service.getBalance("B")).isEqualTo(500);
        assertThat(cache).hasSize(1);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void cachedReceiptRejectsChangedAmountOperationAndAccounts() {
        service.credit("A", 100, "used");

        assertThatThrownBy(() -> service.credit("A", 200, "used"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.debit("A", 100, "used"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.credit("B", 100, "used"))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(service.getBalance("A")).isEqualTo(1100);
        assertThat(service.getBalance("B")).isEqualTo(200);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void failedOperationsRemainRetryableAndAreNotCached() {
        assertThatThrownBy(() -> service.transfer("A", "B", 1200, "retry"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(cache).isEmpty();
        assertThat(transactionCount()).isZero();

        service.credit("A", 500, "funding");
        service.transfer("A", "B", 1200, "retry");

        assertThat(service.getBalance("A")).isEqualTo(300);
        assertThat(service.getBalance("B")).isEqualTo(1400);
        assertThat(cache).hasSize(2);
    }

    @Test
    void rollbackAfterInsertAndReadNeverPublishesUncommittedReceipt() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            store.record(TransactionRecord.credit("rollback", "A", 100));
            assertThat(store.find("rollback")).isPresent();
            assertThat(cache).isEmpty();
            status.setRollbackOnly();
        });

        assertThat(cache).isEmpty();
        assertThat(transactionCount()).isZero();
        service.credit("A", 100, "rollback");
        assertThat(service.getBalance("A")).isEqualTo(1100);
        assertThat(cache).hasSize(1);
    }

    @Test
    void redisOutageDoesNotPreventOperationsOrDurableDuplicateDetection() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        BalanceService isolated = independentService();

        isolated.credit("A", 100, "offline");
        isolated.credit("A", 100, "offline");

        assertThat(service.getBalance("A")).isEqualTo(1100);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(cache).isEmpty();
        verify(values, times(1)).get(anyString());
    }

    @Test
    void cacheFailureAfterCommitDoesNotTurnSuccessIntoAnError() {
        doThrow(new RedisConnectionFailureException("offline"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        BalanceService isolated = independentService();

        isolated.credit("A", 100, "committed");
        isolated.credit("A", 100, "committed");

        assertThat(service.getBalance("A")).isEqualTo(1100);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesAcrossInstancesApplyOnce() throws Exception {
        BalanceService other = independentService();

        concurrently(20, i -> (i % 2 == 0 ? service : other).transfer("A", "B", 300, "duplicate"));

        assertThat(service.getBalance("A")).isEqualTo(700);
        assertThat(service.getBalance("B")).isEqualTo(500);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(cache).hasSize(1);
    }

    @Test
    void concurrentConflictingAccountsCannotOverwriteWinningReceipt() throws Exception {
        BalanceService other = independentService();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        concurrently(2, i -> {
            try {
                (i == 0 ? service : other).credit(i == 0 ? "A" : "B", 100, "shared");
                successes.incrementAndGet();
            } catch (IdempotencyConflictException ex) {
                conflicts.incrementAndGet();
            }
        });

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(service.getBalance("A") + service.getBalance("B")).isEqualTo(1300);
        assertThat(transactionCount()).isEqualTo(1);
        TransactionRecord winner = new JdbcIdempotencyStore(jdbc).find("shared").orElseThrow();
        TransactionRecord cached = mapper.readValue(cache.values().iterator().next(), TransactionRecord.class);
        assertThat(cached).usingRecursiveComparison().ignoringFields("processedAt").isEqualTo(winner);
        // H2 rounds timestamps to microseconds; Redis preserves the original Instant precision.
        assertThat(cached.processedAt()).isCloseTo(winner.processedAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    private BalanceService independentService() {
        return new BalanceServiceImpl(new JdbcAccountRepository(jdbc), new AccountLockManager(),
                new RedisIdempotencyStore(new JdbcIdempotencyStore(jdbc), redis, mapper,
                        Duration.ofHours(24), "balance-service:local:idempotency:v1:", Duration.ofSeconds(5)),
                transactionManager);
    }

    private int transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions", Integer.class);
    }

    private void concurrently(int workers, IntConsumer operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(Math.min(workers, 8))) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start");
                    }
                    operation.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
    }
}
