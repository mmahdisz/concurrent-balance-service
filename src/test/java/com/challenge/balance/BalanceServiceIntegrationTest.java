package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.idempotency.JdbcIdempotencyStore;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.repository.JdbcAccountRepository;
import com.challenge.balance.service.BalanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BalanceServiceIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private BalanceService service;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM processed_transactions");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void restOperationsPersistBalancesAndIdempotencyRecords() throws Exception {
        postJson("/accounts", "{\"accountId\":\"A\",\"initialBalance\":1000}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value(1000));
        postJson("/accounts", "{\"accountId\":\"B\",\"initialBalance\":200}")
                .andExpect(status().isCreated());
        for (int retry = 0; retry < 2; retry++) {
            postJson("/accounts/A/credit", "{\"amount\":500,\"transactionId\":\"credit\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(1500));
        }
        for (int retry = 0; retry < 2; retry++) {
            postJson("/accounts/A/debit", "{\"amount\":100,\"transactionId\":\"debit\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(1400));
        }
        for (int retry = 0; retry < 2; retry++) {
            postJson("/accounts/A/transfer",
                    "{\"destinationAccountId\":\"B\",\"amount\":300,\"transactionId\":\"transfer\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(1100));
        }
        mvc.perform(get("/accounts/B/balance"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(500));
        assertThat(databaseBalance("A")).isEqualTo(1100);
        assertThat(databaseBalance("B")).isEqualTo(500);
        assertThat(transactionCount()).isEqualTo(3);

        // Fresh objects have no shared in-memory state with the application's repositories.
        BalanceService reloaded = independentService();
        reloaded.transfer("A", "B", 300, "transfer");
        assertThat(reloaded.getBalance("A")).isEqualTo(1100);
        assertThat(new JdbcIdempotencyStore(jdbc).find("transfer")).isPresent();
    }

    @Test
    void restErrorsPreserveDatabaseState() throws Exception {
        accounts.create("A", 100);
        accounts.create("B", 200);
        postJson("/accounts", "{\"accountId\":\"A\",\"initialBalance\":999}")
                .andExpect(status().isConflict());
        postJson("/accounts/A/credit", "{\"amount\":0,\"transactionId\":\"invalid\"}")
                .andExpect(status().isBadRequest());
        postJson("/accounts/missing/debit", "{\"amount\":1,\"transactionId\":\"missing\"}")
                .andExpect(status().isNotFound());
        postJson("/accounts/A/transfer",
                "{\"destinationAccountId\":\"B\",\"amount\":500,\"transactionId\":\"funds\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
        service.credit("A", 10, "used");
        postJson("/accounts/A/credit", "{\"amount\":20,\"transactionId\":\"used\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
        assertThat(databaseBalance("A")).isEqualTo(110);
        assertThat(databaseBalance("B")).isEqualTo(200);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void failedTransferCanBeRetriedAfterFunding() {
        accounts.create("A", 100);
        accounts.create("B", 200);
        assertThatThrownBy(() -> service.transfer("A", "B", 500, "retry"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(databaseBalance("A")).isEqualTo(100);
        assertThat(databaseBalance("B")).isEqualTo(200);
        assertThat(transactionCount()).isZero();
        service.credit("A", 1000, "funding");
        service.transfer("A", "B", 500, "retry");
        assertThat(databaseBalance("A")).isEqualTo(600);
        assertThat(databaseBalance("B")).isEqualTo(700);
        assertThat(transactionCount()).isEqualTo(2);
    }

    @Test
    void databaseFailureAfterSourceUpdateRollsBackEntireTransfer() {
        accounts.create("A", 1000);
        accounts.create("B", Long.MAX_VALUE);
        // Overflow violates the database's nonnegative balance constraint on the second update.
        assertThatThrownBy(() -> service.transfer("A", "B", 1, "rollback"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(databaseBalance("A")).isEqualTo(1000);
        assertThat(databaseBalance("B")).isEqualTo(Long.MAX_VALUE);
        assertThat(transactionCount()).isZero();
        service.debit("B", 1, "make-room");
        service.transfer("A", "B", 1, "rollback");
        assertThat(databaseBalance("A")).isEqualTo(999);
        assertThat(databaseBalance("B")).isEqualTo(Long.MAX_VALUE);
        assertThat(transactionCount()).isEqualTo(2);
    }

    @Test
    void concurrentWithdrawalsAcrossServiceInstancesCannotDoubleSpend() throws Exception {
        accounts.create("A", 1000);
        BalanceService other = independentService();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        concurrently(2, i -> {
            try {
                (i == 0 ? service : other).debit("A", 700, "debit-" + i);
                successes.incrementAndGet();
            } catch (InsufficientFundsException ex) {
                rejected.incrementAndGet();
            }
        });
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(databaseBalance("A")).isEqualTo(300);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateTransfersAcrossServiceInstancesApplyOnce() throws Exception {
        accounts.create("A", 1000);
        accounts.create("B", 200);
        BalanceService other = independentService();
        concurrently(20, i -> (i % 2 == 0 ? service : other).transfer("A", "B", 700, "duplicate"));
        assertThat(databaseBalance("A")).isEqualTo(300);
        assertThat(databaseBalance("B")).isEqualTo(900);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void concurrentTransactionIdReuseOnDifferentAccountsHasOnlyOneWinner() throws Exception {
        accounts.create("A", 1000);
        accounts.create("B", 1000);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        concurrently(2, i -> {
            try {
                service.credit(i == 0 ? "A" : "B", 100, "shared");
                successes.incrementAndGet();
            } catch (IdempotencyConflictException ex) {
                conflicts.incrementAndGet();
            }
        });
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(databaseBalance("A") + databaseBalance("B")).isEqualTo(2100);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void oppositeTransfersAcrossServiceInstancesCompleteWithoutDeadlock() throws Exception {
        accounts.create("A", 1000);
        accounts.create("B", 1000);
        BalanceService other = independentService();
        concurrently(2, i -> {
            for (int n = 0; n < 50; n++) {
                (i == 0 ? service : other).transfer(i == 0 ? "A" : "B", i == 0 ? "B" : "A",
                        1, "opposite-" + i + "-" + n);
            }
        });
        assertThat(databaseBalance("A")).isEqualTo(1000);
        assertThat(databaseBalance("B")).isEqualTo(1000);
        assertThat(transactionCount()).isEqualTo(100);
    }

    private BalanceService independentService() {
        return new BalanceServiceImpl(new JdbcAccountRepository(jdbc), new AccountLockManager(),
                new JdbcIdempotencyStore(jdbc), transactionManager);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long databaseBalance(String id) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, id);
    }

    private int transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions", Integer.class);
    }

    private void concurrently(int callers, IntConsumer action) throws Exception {
        var pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }
                    action.accept(index);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
