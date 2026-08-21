package com.challenge.balance.service;

import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.exception.AccountNotFoundException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.exception.SameAccountTransferException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferOperationTest {

    private InMemoryAccountRepository repository;
    private BalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        service = new BalanceServiceImpl(repository, new AccountLockManager(), new IdempotencyStore());
    }

    @Test
    void transferMovesExactAmountBetweenAccounts() {
        repository.create("A", 1_000);
        repository.create("B", 500);

        service.transfer("A", "B", 300, "TX-1");

        assertThat(service.getBalance("A")).isEqualTo(700);
        assertThat(service.getBalance("B")).isEqualTo(800);
    }

    @Test
    void transferPreservesTotalAcrossBothAccounts() {
        repository.create("A", 1_000);
        repository.create("B", 500);
        long totalBefore = service.getBalance("A") + service.getBalance("B");

        service.transfer("A", "B", 300, "TX-1");

        long totalAfter = service.getBalance("A") + service.getBalance("B");
        assertThat(totalAfter).isEqualTo(totalBefore);
    }

    @Test
    void transferFailsAndLeavesBothBalancesUnchangedWhenSourceHasInsufficientFunds() {
        repository.create("A", 100);
        repository.create("B", 500);

        assertThrows(InsufficientFundsException.class, () -> service.transfer("A", "B", 1_000, "TX-1"));

        assertThat(service.getBalance("A")).isEqualTo(100);
        assertThat(service.getBalance("B")).isEqualTo(500);
    }

    @Test
    void transferRejectsZeroOrNegativeAmount() {
        repository.create("A", 1_000);
        repository.create("B", 500);

        assertThrows(InvalidAmountException.class, () -> service.transfer("A", "B", 0, "TX-1"));
    }

    @Test
    void transferRejectsUnknownSourceAccount() {
        repository.create("B", 500);

        assertThrows(AccountNotFoundException.class, () -> service.transfer("UNKNOWN", "B", 100, "TX-1"));
    }

    @Test
    void transferRejectsUnknownDestinationAccount() {
        repository.create("A", 1_000);

        assertThrows(AccountNotFoundException.class, () -> service.transfer("A", "UNKNOWN", 100, "TX-1"));
    }

    /**
     * Design decision (see README "Same-account transfer"): a transfer
     * where source == destination is rejected outright rather than
     * silently treated as a no-op, so that a caller bug (e.g. accidentally
     * passing the same id twice) surfaces immediately instead of silently
     * doing nothing.
     */
    @Test
    void transferRejectsSameSourceAndDestination() {
        repository.create("A", 1_000);

        assertThrows(SameAccountTransferException.class, () -> service.transfer("A", "A", 100, "TX-1"));
        assertThat(service.getBalance("A")).isEqualTo(1_000);
    }
}
