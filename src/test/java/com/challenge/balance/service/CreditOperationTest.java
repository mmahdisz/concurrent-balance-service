package com.challenge.balance.service;

import java.math.BigDecimal;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.AccountNotFoundException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.idempotency.InMemoryIdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreditOperationTest {

    private InMemoryAccountRepository repository;
    private BalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new InMemoryIdempotencyStore());
    }

    @Test
    void creditIncreasesBalance() {
        repository.create("A", BigDecimal.valueOf(1_000));

        service.credit("A", BigDecimal.valueOf(500), "TX-1");

        assertThat(service.getBalance("A")).isEqualByComparingTo(BigDecimal.valueOf(1_500));
    }

    @Test
    void creditRejectsZeroAmount() {
        repository.create("A", BigDecimal.valueOf(1_000));

        assertThrows(InvalidAmountException.class, () -> service.credit("A", BigDecimal.valueOf(0), "TX-1"));
        assertThat(service.getBalance("A")).isEqualByComparingTo(BigDecimal.valueOf(1_000));
    }

    @Test
    void creditRejectsNegativeAmount() {
        repository.create("A", BigDecimal.valueOf(1_000));

        assertThrows(InvalidAmountException.class, () -> service.credit("A", BigDecimal.valueOf(-50), "TX-1"));
        assertThat(service.getBalance("A")).isEqualByComparingTo(BigDecimal.valueOf(1_000));
    }

    @Test
    void creditRejectsUnknownAccount() {
        assertThrows(AccountNotFoundException.class, () -> service.credit("UNKNOWN", BigDecimal.valueOf(100), "TX-1"));
    }
}
