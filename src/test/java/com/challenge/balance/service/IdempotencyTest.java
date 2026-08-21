package com.challenge.balance.service;

import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyTest {

    private InMemoryAccountRepository repository;
    private BalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new IdempotencyStore());
    }

    // --- Sequential retries -------------------------------------------------

    @Test
    void repeatedCreditWithSameTransactionIdAppliesOnlyOnce() {
        repository.create("A", 1_000);

        service.credit("A", 100, "TX-1");
        service.credit("A", 100, "TX-1");
        service.credit("A", 100, "TX-1");

        assertThat(service.getBalance("A")).isEqualTo(1_100);
    }

    @Test
    void repeatedDebitWithSameTransactionIdAppliesOnlyOnce() {
        repository.create("A", 1_000);

        service.debit("A", 200, "TX-1");
        service.debit("A", 200, "TX-1");
        service.debit("A", 200, "TX-1");

        assertThat(service.getBalance("A")).isEqualTo(800);
    }

    @Test
    void repeatedTransferWithSameTransactionIdAppliesOnlyOnce() {
        repository.create("A", 1_000);
        repository.create("B", 1_000);

        service.transfer("A", "B", 300, "TX-1");
        service.transfer("A", "B", 300, "TX-1");
        service.transfer("A", "B", 300, "TX-1");

        assertThat(service.getBalance("A")).isEqualTo(700);
        assertThat(service.getBalance("B")).isEqualTo(1_300);
    }

    @Test
    void retryAfterAGenuinelyFailedAttemptIsFreeToSucceedLater() {
        // The first attempt fails validation (insufficient funds) and is
        // therefore never recorded. A later retry of the same transactionId
        // - after the balance has grown enough - is not a "double apply"
        // because nothing was ever applied the first time.
        repository.create("A", 100);

        assertThrows(InsufficientFundsException.class, () -> service.debit("A", 500, "TX-1"));

        service.credit("A", 1_000, "TX-2");
        service.debit("A", 500, "TX-1"); // retry, now succeeds

        assertThat(service.getBalance("A")).isEqualTo(600);
    }

    @Test
    void reusingATransactionIdWithDifferentParametersIsRejected() {
        repository.create("A", 1_000);

        service.credit("A", 100, "TX-1");

        assertThrows(IdempotencyConflictException.class, () -> service.credit("A", 200, "TX-1"));
        // the original, successfully-applied effect must not be touched
        assertThat(service.getBalance("A")).isEqualTo(1_100);
    }

    // --- Concurrent duplicates -----------------------------------------------
    // "ممکن است درخواستهای تکراری بهصورت همزمان دریافت شوند" - duplicate
    // requests carrying the SAME transactionId may arrive at the same time.

    @Test
    void concurrentDuplicateCreditsApplyExactlyOnce() throws InterruptedException {
        repository.create("A", 1_000);
        int callers = 50;

        runConcurrently(callers, i -> service.credit("A", 500, "TX-DUP"));

        assertThat(service.getBalance("A")).isEqualTo(1_500);
    }

    @Test
    void concurrentDuplicateDebitsApplyExactlyOnce() throws InterruptedException {
        repository.create("A", 1_000);
        int callers = 50;

        runConcurrently(callers, i -> service.debit("A", 500, "TX-DUP"));

        assertThat(service.getBalance("A")).isEqualTo(500);
    }

    @Test
    void concurrentDuplicateTransfersApplyExactlyOnce() throws InterruptedException {
        repository.create("A", 1_000);
        repository.create("B", 1_000);
        int callers = 50;

        runConcurrently(callers, i -> service.transfer("A", "B", 300, "TX-DUP"));

        assertThat(service.getBalance("A")).isEqualTo(700);
        assertThat(service.getBalance("B")).isEqualTo(1_300);
    }

    /** Fires {@code callers} threads at {@code task} as simultaneously as possible. */
    private void runConcurrently(int callers, java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(callers, 32));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    task.accept(idx);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).as("all callers finished before timeout").isTrue();
        pool.shutdown();
    }
}
