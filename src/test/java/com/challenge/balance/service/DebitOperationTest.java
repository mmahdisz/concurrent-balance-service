package com.challenge.balance.service;

import java.math.BigDecimal;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.AccountNotFoundException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.idempotency.InMemoryIdempotencyStore;
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
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new InMemoryIdempotencyStore());
    }

    @Test
    void debitDecreasesBalanceWhenSufficient() {
        repository.create("A", BigDecimal.valueOf(1_000));

        service.debit("A", BigDecimal.valueOf(700), "TX-1");

        assertThat(service.getBalance("A")).isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    @Test
    void debitFailsAndLeavesBalanceUnchangedWhenInsufficient() {
        repository.create("A", BigDecimal.valueOf(1_000));

        assertThrows(InsufficientFundsException.class, () -> service.debit("A", BigDecimal.valueOf(1_200), "TX-1"));

        assertThat(service.getBalance("A")).isEqualByComparingTo(BigDecimal.valueOf(1_000));
    }

    @Test
    void debitAtExactBalanceSucceeds() {
        repository.create("A", BigDecimal.valueOf(1_000));

        service.debit("A", BigDecimal.valueOf(1_000), "TX-1");

        assertThat(service.getBalance("A")).isZero();
    }

    @Test
    void debitRejectsZeroOrNegativeAmount() {
        repository.create("A", BigDecimal.valueOf(1_000));

        assertThrows(InvalidAmountException.class, () -> service.debit("A", BigDecimal.valueOf(0), "TX-1"));
        assertThrows(InvalidAmountException.class, () -> service.debit("A", BigDecimal.valueOf(-1), "TX-2"));
        assertThat(service.getBalance("A")).isEqualByComparingTo(BigDecimal.valueOf(1_000));
    }

    @Test
    void debitRejectsUnknownAccount() {
        assertThrows(AccountNotFoundException.class, () -> service.debit("UNKNOWN", BigDecimal.valueOf(100), "TX-1"));
    }
}
