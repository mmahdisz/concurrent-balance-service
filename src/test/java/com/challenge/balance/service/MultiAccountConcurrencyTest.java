package com.challenge.balance.service;

import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAccountConcurrencyTest {

    private InMemoryAccountRepository repository;
    private BalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new IdempotencyStore());
    }

    /**
     * Operations on independent accounts must not serialize behind each
     * other. This does not assert on wall-clock timing (timing-based
     * concurrency assertions are flaky); instead it demonstrates
     * independence functionally: N accounts are each hammered concurrently
     * by their own thread and every single one reaches the exact expected
     * balance, which would not happen if all accounts secretly shared one
     * lock and some updates got lost due to accidental interleaving bugs.
     */
    @Test
    void concurrentOperationsOnDifferentAccountsAreIndependentlyCorrect() throws InterruptedException {
        int numAccounts = 20;
        long initial = 1_000;
        for (int i = 0; i < numAccounts; i++) {
            repository.create("ACC-" + i, initial);
        }

        int opsPerAccount = 200;
        ExecutorService pool = Executors.newFixedThreadPool(numAccounts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numAccounts);

        for (int a = 0; a < numAccounts; a++) {
            String accountId = "ACC-" + a;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int op = 0; op < opsPerAccount; op++) {
                        service.credit(accountId, 10, accountId + "-TX-" + op);
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

        for (int i = 0; i < numAccounts; i++) {
            assertThat(service.getBalance("ACC-" + i)).isEqualTo(initial + (long) opsPerAccount * 10);
        }
    }

    /**
     * Many threads perform random transfers across a shared pool of
     * accounts concurrently. Transfer preserves the sum of any two accounts
     * it touches, so the sum across ALL accounts must be exactly preserved
     * no matter how the operations interleave, and no account may ever go
     * negative. This is the strongest single check that transfer really is
     * atomic under real contention (as opposed to only in a single-threaded
     * test).
     */
    @Test
    void concurrentRandomTransfersPreserveTotalBalanceAndNeverGoNegative() throws InterruptedException {
        int numAccounts = 10;
        long initialEach = 10_000;
        for (int i = 0; i < numAccounts; i++) {
            repository.create("ACC-" + i, initialEach);
        }
        long totalBefore = (long) numAccounts * initialEach;

        int threadCount = 20;
        int opsPerThread = 200;
        Random seedRandom = new Random(42);
        List<Long> seeds = IntStream.range(0, threadCount).mapToObj(i -> seedRandom.nextLong()).toList();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger txSeq = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            Random rnd = new Random(seeds.get(t));
            pool.submit(() -> {
                try {
                    start.await();
                    for (int op = 0; op < opsPerThread; op++) {
                        int a = rnd.nextInt(numAccounts);
                        int b;
                        do {
                            b = rnd.nextInt(numAccounts);
                        } while (b == a);
                        long amount = 1 + rnd.nextInt(50);
                        String txId = "TX-MULTI-" + txSeq.incrementAndGet();
                        try {
                            service.transfer("ACC-" + a, "ACC-" + b, amount, txId);
                        } catch (InsufficientFundsException expectedSometimes) {
                            // fine - not every random transfer has enough balance behind it
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        long totalAfter = 0;
        for (int i = 0; i < numAccounts; i++) {
            long balance = service.getBalance("ACC-" + i);
            assertThat(balance).isGreaterThanOrEqualTo(0);
            totalAfter += balance;
        }
        assertThat(totalAfter).isEqualTo(totalBefore);
    }

    /**
     * Two threads transfer in OPPOSITE directions between the same pair of
     * accounts as fast as possible. If lock acquisition order depended on
     * call-argument order instead of a canonical global order, this is
     * exactly the scenario that deadlocks (thread 1 holds A, wants B; thread
     * 2 holds B, wants A). Completing within the timeout is the test; a
     * broken implementation hangs forever here instead of failing loudly,
     * which is why the timeout + shutdownNow is important.
     */
    @Test
    void bidirectionalConcurrentTransfersCompleteWithoutDeadlock() throws InterruptedException {
        repository.create("A", 1_000_000);
        repository.create("B", 1_000_000);
        int iterationsPerDirection = 3_000;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger txSeq = new AtomicInteger();

        pool.submit(() -> {
            for (int i = 0; i < iterationsPerDirection; i++) {
                service.transfer("A", "B", 1, "TX-AB-" + txSeq.incrementAndGet());
            }
            done.countDown();
        });
        pool.submit(() -> {
            for (int i = 0; i < iterationsPerDirection; i++) {
                service.transfer("B", "A", 1, "TX-BA-" + txSeq.incrementAndGet());
            }
            done.countDown();
        });

        boolean completed = done.await(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(completed).as("bidirectional transfers must not deadlock").isTrue();
        assertThat(service.getBalance("A") + service.getBalance("B")).isEqualTo(2_000_000L);
    }
}
