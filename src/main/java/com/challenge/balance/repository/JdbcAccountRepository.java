package com.challenge.balance.repository;

import java.math.BigDecimal;
import com.challenge.balance.domain.Account;
import com.challenge.balance.exception.AccountAlreadyExistsException;
import com.challenge.balance.exception.AccountNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Account getOrThrow(String accountId) {
        return find(accountId, false);
    }

    @Override
    public Account lockForUpdate(String accountId) {
        return find(accountId, true);
    }

    private Account find(String accountId, boolean forUpdate) {
        return jdbc.query("SELECT id, balance FROM accounts WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                        (rs, row) -> new Account(rs.getString("id"), rs.getBigDecimal("balance")), accountId)
                .stream().findFirst().orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Override
    public Account create(String accountId, BigDecimal initialBalance) {
        Account account = new Account(accountId, initialBalance);
        try {
            jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, ?)", accountId, initialBalance);
        } catch (DuplicateKeyException ex) {
            throw new AccountAlreadyExistsException(accountId);
        }
        return account;
    }

    @Override
    public void save(Account account) {
        if (jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", account.getBalance(), account.getId()) != 1) {
            throw new AccountNotFoundException(account.getId());
        }
    }

    @Override
    public boolean exists(String accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM accounts WHERE id = ?)", Boolean.class, accountId));
    }
}
