package com.challenge.balance.service;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.events.BalanceEventRecorder;
import java.util.Optional;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.domain.Account;
import com.challenge.balance.domain.TransactionRecord;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.exception.SameAccountTransferException;
import com.challenge.balance.idempotency.IdempotencyStore;
import com.challenge.balance.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.BiConsumer;

@Service
public class BalanceServiceImpl implements BalanceService {

    private final AccountRepository accountRepository;
    private final AccountLockManager lockManager;
    private final IdempotencyStore idempotencyStore;
    private final TransactionTemplate transactions;
    private final Optional<BalanceEventRecorder> eventRecorder;

    public BalanceServiceImpl(AccountRepository accountRepository, AccountLockManager lockManager,
                              IdempotencyStore idempotencyStore) {
        this(accountRepository, lockManager, idempotencyStore, null);
    }

    public BalanceServiceImpl(AccountRepository accountRepository, AccountLockManager lockManager,
                              IdempotencyStore idempotencyStore, PlatformTransactionManager transactionManager) {
        this(accountRepository, lockManager, idempotencyStore, transactionManager, Optional.empty());
    }

    @Autowired
    public BalanceServiceImpl(AccountRepository accountRepository, AccountLockManager lockManager,
                              IdempotencyStore idempotencyStore, PlatformTransactionManager transactionManager,
                              Optional<BalanceEventRecorder> eventRecorder) {
        this.eventRecorder = eventRecorder;
        this.accountRepository = accountRepository;
        this.lockManager = lockManager;
        this.idempotencyStore = idempotencyStore;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        validateAmount(amount);
        lockManager.withLock(accountId, () -> apply(TransactionRecord.credit(transactionId, accountId, amount), (account, ignored) -> {
            account.credit(amount);
            accountRepository.save(account);
        }));
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        validateAmount(amount);
        lockManager.withLock(accountId, () -> apply(TransactionRecord.debit(transactionId, accountId, amount), (account, ignored) -> {
            checkFunds(account, amount);
            account.debit(amount);
            accountRepository.save(account);
        }));
    }

    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        validateAmount(amount);
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new SameAccountTransferException(sourceAccountId);
        }
        lockManager.withLocks(sourceAccountId, destinationAccountId, () -> apply(
                TransactionRecord.transfer(transactionId, sourceAccountId, destinationAccountId, amount), (source, destination) -> {
                    checkFunds(source, amount);
                    source.debit(amount);
                    destination.credit(amount);
                    accountRepository.save(source);
                    accountRepository.save(destination);
                }));
    }

    @Override
    public long getBalance(String accountId) {
        return accountRepository.getOrThrow(accountId).getBalance();
    }

    private void apply(TransactionRecord record, BiConsumer<Account, Account> mutation) {
        Runnable action = () -> {
            Account primary;
            Account secondary = null;
            String primaryId = record.primaryAccountId();
            String secondaryId = record.secondaryAccountId();
            if (secondaryId == null) {
                primary = accountRepository.lockForUpdate(primaryId);
            } else {
                // Consistent ordering prevents opposite transfers from deadlocking across instances.
                boolean primaryFirst = primaryId.compareTo(secondaryId) < 0;
                Account first = accountRepository.lockForUpdate(primaryFirst ? primaryId : secondaryId);
                Account second = accountRepository.lockForUpdate(primaryFirst ? secondaryId : primaryId);
                primary = primaryFirst ? first : second;
                secondary = primaryFirst ? second : first;
            }
            if (alreadyApplied(record)) {
                return;
            }
            mutation.accept(primary, secondary);
            idempotencyStore.record(record);
            if (eventRecorder.isPresent()) {
                eventRecorder.get().record(record, primary, secondary);
            }
        };
        if (transactions == null) {
            action.run();
            return;
        }
        try {
            // Commit before releasing the account locks; never expose an uncommitted balance.
            transactions.executeWithoutResult(status -> action.run());
        } catch (DuplicateKeyException ex) {
            // A different service instance or unrelated account may have won this transaction ID.
            // The losing mutation has rolled back before checking the committed winner.
            if (!alreadyApplied(record)) {
                throw ex;
            }
        }
    }

    private boolean alreadyApplied(TransactionRecord record) {
        return idempotencyStore.find(record.transactionId()).map(existing -> {
            if (!existing.matches(record.type(), record.primaryAccountId(), record.secondaryAccountId(), record.amount())) {
                throw new IdempotencyConflictException(record.transactionId());
            }
            return true;
        }).orElse(false);
    }

    private void checkFunds(Account account, long amount) {
        if (account.getBalance() < amount) {
            throw new InsufficientFundsException(account.getId(), amount, account.getBalance());
        }
    }

    private void validateAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException(amount);
        }
    }
}
