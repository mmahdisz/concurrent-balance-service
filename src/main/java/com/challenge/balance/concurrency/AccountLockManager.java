package com.challenge.balance.concurrency;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;


@Component
public class AccountLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(String accountId) {
        return locks.computeIfAbsent(accountId, id -> new ReentrantLock());
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
        String first = idA.compareTo(idB) <= 0 ? idA : idB;
        String second = idA.compareTo(idB) <= 0 ? idB : idA;

        ReentrantLock lock1 = lockFor(first);
        ReentrantLock lock2 = lockFor(second);

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
