package com.challenge.balance.concurrency;

import org.springframework.stereotype.Component;

import java.util.stream.IntStream;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;


@Component
public class AccountLockManager {

    // Fixed stripes bound memory even when callers supply unlimited unknown account IDs.
    private static final int STRIPE_COUNT = 1024;
    private final ReentrantLock[] locks = IntStream.range(0, STRIPE_COUNT)
            .mapToObj(ignored -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    private ReentrantLock lockFor(String accountId) {
        return locks[stripeIndex(accountId)];
    }

    private int stripeIndex(String accountId) {
        int hash = accountId.hashCode();
        return (hash ^ (hash >>> 16)) & (STRIPE_COUNT - 1);
    }

    public <T> T withLock(String accountId, Supplier<T> action) {
        ReentrantLock lock = lockFor(accountId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(String accountId, Runnable action) {
        withLock(accountId, () -> {
            action.run();
            return null;
        });
    }


    public <T> T withLocks(String idA, String idB, Supplier<T> action) {
        // Order actual stripes: account-ID order can deadlock when different IDs collide.
        int stripeA = stripeIndex(idA);
        int stripeB = stripeIndex(idB);
        ReentrantLock lock1 = locks[Math.min(stripeA, stripeB)];
        ReentrantLock lock2 = locks[Math.max(stripeA, stripeB)];

        lock1.lock();
        try {
            lock2.lock();
            try {
                return action.get();
            } finally {
                lock2.unlock();
            }
        } finally {
            lock1.unlock();
        }
    }

    public void withLocks(String idA, String idB, Runnable action) {
        withLocks(idA, idB, () -> {
            action.run();
            return null;
        });
    }
}
