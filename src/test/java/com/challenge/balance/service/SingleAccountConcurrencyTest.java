package com.challenge.balance.service;

import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SingleAccountConcurrencyTest {

    private InMemoryAccountRepository repository;
    private BalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new IdempotencyStore());
    }

    /**
     * The canonical "double-spend" scenario from the spec: balance = 1000,
     * two threads each try to debit 700 (with DISTINCT transactionIds - this
     * is a genuine race between two different transactions, not a duplicate
     * of one transaction). Exactly one must succeed; the balance must never
     * go negative. Repeated to increase the chance of catching a race if the
     * locking were ever broken.
     */
    @RepeatedTest(20)
    void exactlyOneOfTwoConcurrentDebitsExceedingBalanceSucceeds() throws InterruptedException {
        repository.create("A", 1_000);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.debit("A", 700, "TX-RACE-" + idx);
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    failed.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(failed.get()).isEqualTo(1);
        assertThat(service.getBalance("A")).isGreaterThanOrEqualTo(0);
        assertThat(service.getBalance("A")).isEqualTo(300); // 1000 - one successful 700 debit
    }

    /**
     * High-volume concurrent load on a single account (the account the spec
     * explicitly calls out: "Initial Balance = 100,000 / 1,000 concurrent
     * operations"). Every operation uses a distinct transactionId and is
     * guaranteed to succeed (there's always enough balance), so the final
     * balance is fully deterministic - any deviation means an update was
     * lost or double-applied.
     */
    @Test
    void thousandConcurrentCreditsOnOneAccountProduceExactExpectedBalance() throws InterruptedException {
        long initial = 100_000;
        repository.create("A", initial);
        int operations = 1_000;
        long amountEach = 37;

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(operations);
        for (int i = 0; i < operations; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.credit("A", amountEach, "TX-HC-" + idx);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(service.getBalance("A")).isEqualTo(initial + (long) operations * amountEach);
    }

    /**
     * Mixed credits and debits, many of which are individually guaranteed to
     * fail (insufficient funds) by design - this maximizes contention on the
     * check-then-act sequence inside debit(). The only invariant checked is
     * "never negative", which would be violated immediately by a lost update
     * or a broken check-then-act.
     */
    @Test
    void highContentionMixedDebitsNeverProduceANegativeBalance() throws InterruptedException {
        repository.create("A", 10_000);
        int operations = 2_000;

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(operations);
        for (int i = 0; i < operations; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        service.debit("A", 100, "TX-MIX-" + idx);
                    } catch (InsufficientFundsException ignored) {
                        // expected once the balance runs low; not a failure
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(service.getBalance("A")).isGreaterThanOrEqualTo(0);
        // exactly 100 debits of 100 can succeed against an initial 10,000
        assertThat(service.getBalance("A")).isEqualTo(0);
    }
}
