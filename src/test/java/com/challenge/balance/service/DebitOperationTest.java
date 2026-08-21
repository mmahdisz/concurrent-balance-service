package com.challenge.balance.service;

import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.AccountNotFoundException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebitOperationTest {

    private InMemoryAccountRepository repository;
    private BalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new IdempotencyStore());
    }

    @Test
    void debitDecreasesBalanceWhenSufficient() {
        repository.create("A", 1_000);

        service.debit("A", 700, "TX-1");

        assertThat(service.getBalance("A")).isEqualTo(300);
    }

    @Test
    void debitFailsAndLeavesBalanceUnchangedWhenInsufficient() {
        repository.create("A", 1_000);

        assertThrows(InsufficientFundsException.class, () -> service.debit("A", 1_200, "TX-1"));

        assertThat(service.getBalance("A")).isEqualTo(1_000);
    }

    @Test
    void debitAtExactBalanceSucceeds() {
        repository.create("A", 1_000);

        service.debit("A", 1_000, "TX-1");

        assertThat(service.getBalance("A")).isZero();
    }

    @Test
    void debitRejectsZeroOrNegativeAmount() {
        repository.create("A", 1_000);

        assertThrows(InvalidAmountException.class, () -> service.debit("A", 0, "TX-1"));
        assertThrows(InvalidAmountException.class, () -> service.debit("A", -1, "TX-2"));
        assertThat(service.getBalance("A")).isEqualTo(1_000);
    }

    @Test
    void debitRejectsUnknownAccount() {
        assertThrows(AccountNotFoundException.class, () -> service.debit("UNKNOWN", 100, "TX-1"));
    }
}
