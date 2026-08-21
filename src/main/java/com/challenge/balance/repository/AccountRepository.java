package com.challenge.balance.repository;

import com.challenge.balance.domain.Account;

public interface AccountRepository {

    Account getOrThrow(String accountId);

    Account create(String accountId, long initialBalance);

    boolean exists(String accountId);
}
