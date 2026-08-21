package com.challenge.balance.repository;

import com.challenge.balance.domain.Account;
import com.challenge.balance.exception.AccountAlreadyExistsException;
import com.challenge.balance.exception.AccountNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public Account getOrThrow(
            String accountId
    ) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    @Override
    public Account create(
            String accountId,
            long initialBalance
    ) {
        Account account = new Account(accountId, initialBalance);
        Account existing = accounts.putIfAbsent(accountId, account);
        if (existing != null) {
            throw new AccountAlreadyExistsException(accountId);
        }
        return account;
    }

    @Override
    public boolean exists(String accountId) {
        return accounts.containsKey(accountId);
    }
}
