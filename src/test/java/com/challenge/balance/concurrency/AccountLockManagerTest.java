package com.challenge.balance.concurrency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountLockManagerTest {
    @Test
    void failedOperationsOnUniqueIdsDoNotGrowLockStorage() throws Exception {
        var manager = new AccountLockManager();
        var field = AccountLockManager.class.getDeclaredField("locks");
        field.setAccessible(true);
        var original = ((ReentrantLock[]) field.get(manager)).clone();
        for (int i = 0; i < 10_000; i++) {
            String id = "missing-" + i;
            assertThatThrownBy(() -> manager.withLock(id, (Runnable) () -> {
                throw new IllegalArgumentException("Unknown account");
            })).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat((ReentrantLock[]) field.get(manager)).containsExactly(original);
        assertThat(original).allMatch(lock -> !lock.isLocked());
    }

    @Test
    void collidingAccountPairsCompleteAndProtectSharedState() throws Exception {
        var manager = new AccountLockManager();
        // Aa and BB have identical hashes. These pairs reverse hash order when sorted by ID.
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        String[][] pairs = {{"Aa", "B"}, {"B", "BB"}, {"Aa", "BB"}, {"BB", "Aa"}};
        int[] counter = {0};
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4, task -> {
            var thread = new Thread(task);
            thread.setDaemon(true); // A lock-order regression must fail rather than hang the JVM.
            return thread;
        });
        try {
            var futures = new ArrayList<Future<?>>();
            for (var pair : pairs) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 5_000; i++) {
                        manager.withLocks(pair[0], pair[1], () -> { counter[0]++; });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertThat(counter[0]).isEqualTo(20_000);
        } finally {
            pool.shutdownNow();
        }
    }
}
