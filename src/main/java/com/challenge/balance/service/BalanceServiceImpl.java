package com.challenge.balance.service;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.domain.Account;
import com.challenge.balance.domain.OperationType;
import com.challenge.balance.domain.TransactionRecord;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.exception.SameAccountTransferException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
public class BalanceServiceImpl implements BalanceService {

    private final AccountRepository accountRepository;
    private final AccountLockManager lockManager;
    private final IdempotencyStore idempotencyStore;


    public BalanceServiceImpl(
            AccountRepository accountRepository,
            AccountLockManager lockManager,
            IdempotencyStore idempotencyStore
    ) {
        this.accountRepository = accountRepository;
        this.lockManager = lockManager;
        this.idempotencyStore = idempotencyStore;
    }


    @Override
    public void credit(
            String accountId,
            long amount,
            String transactionId
    ) {
        validateAmount(amount);
        Account account = accountRepository.getOrThrow(accountId);

        lockManager.withLock(accountId, () -> {
            if (alreadyApplied(transactionId, OperationType.CREDIT, accountId, null, amount)) {
                return;
            }
            account.credit(amount);
            idempotencyStore.record(TransactionRecord.credit(transactionId, accountId, amount));
        });
    }


    @Override
    public void debit(
            String accountId,
            long amount,
            String transactionId
    ) {
        validateAmount(amount);
        Account account = accountRepository.getOrThrow(accountId);

        lockManager.withLock(accountId, () -> {
            if (alreadyApplied(transactionId, OperationType.DEBIT, accountId, null, amount)) {
                return;
            }
            long balance = account.getBalance();
            if (balance < amount) {
                throw new InsufficientFundsException(accountId, amount, balance);
            }
            account.debit(amount);
            idempotencyStore.record(TransactionRecord.debit(transactionId, accountId, amount));
        });
    }


    @Override
    public void transfer(
            String sourceAccountId,
            String destinationAccountId,
            long amount,
            String transactionId
    ) {
        validateAmount(amount);
        if (sourceAccountId.equals(destinationAccountId))
            throw new SameAccountTransferException(sourceAccountId);

        Account source = accountRepository.getOrThrow(sourceAccountId);
        Account destination = accountRepository.getOrThrow(destinationAccountId);

        lockManager.withLocks(sourceAccountId, destinationAccountId, () -> {
            if (alreadyApplied(transactionId, OperationType.TRANSFER, sourceAccountId, destinationAccountId, amount)) {
                return;
            }
            long balance = source.getBalance();
            if (balance < amount) {
                throw new InsufficientFundsException(sourceAccountId, amount, balance);
            }

            source.debit(amount);
            destination.credit(amount);
            idempotencyStore.record(
                    TransactionRecord.transfer(transactionId, sourceAccountId, destinationAccountId, amount));
        });
    }

    @Override
    public long getBalance(String accountId) {
        return accountRepository.getOrThrow(accountId).getBalance();
    }

    private void validateAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException(amount);
        }
    }


    private boolean alreadyApplied(
            String transactionId,
            OperationType type,
            String primaryAccountId,
            String secondaryAccountId,
            long amount
    ) {
        Optional<TransactionRecord> existing = idempotencyStore.find(transactionId);
        if (existing.isEmpty())
            return false;

        if (!existing.get().matches(type, primaryAccountId, secondaryAccountId, amount))
            throw new IdempotencyConflictException(transactionId);

        return true;
    }
}
