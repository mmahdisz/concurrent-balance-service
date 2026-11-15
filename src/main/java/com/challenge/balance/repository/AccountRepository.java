package com.challenge.balance.repository;

import java.math.BigDecimal;
import com.challenge.balance.domain.Account;

public interface AccountRepository {

    Account getOrThrow(String accountId);

    default Account lockForUpdate(String accountId) {
        return getOrThrow(accountId);
    }

    default void save(Account account) {
        // In-memory accounts are updated directly while holding their account lock.
    }

    Account create(String accountId, BigDecimal initialBalance);

    boolean exists(String accountId);
}
