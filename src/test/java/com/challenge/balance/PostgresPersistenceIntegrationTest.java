package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.events.JdbcBalanceEventOutbox;
import com.challenge.balance.idempotency.JdbcIdempotencyStore;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.service.BalanceServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL migration, constraint, and transactional outbox guarantees. */
@SpringBootTest(properties = {
        "balance.kafka.enabled=true", "balance.kafka.publisher.enabled=false",
        "balance.kafka.create-topic=false", "idempotency.redis.enabled=false"
})
class PostgresPersistenceIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired AccountRepository accounts;
    @Autowired BalanceService service;
    @Autowired JdbcBalanceEventOutbox outbox;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM kafka_outbox");
        jdbc.update("DELETE FROM processed_transactions");
        jdbc.update("DELETE FROM accounts");
    }

    @Test
    void migrationIsValidatedAndDoesNotRepeatOrLoseData() {
        accounts.create("persistent", 42);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(service.getBalance("persistent")).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kafka_outbox_lock", Integer.class)).isEqualTo(1);
    }

    @Test
    void postgresConstraintsRejectNegativeBalancesAndOrphanReceipts() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, ?)", "negative", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO processed_transactions
                    (transaction_id, operation_type, primary_account_id, amount, processed_at)
                VALUES ('orphan', 'CREDIT', 'missing', 1, CURRENT_TIMESTAMP)
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transferWritesJsonEventsAndDurableReceiptExactlyOnce() throws Exception {
        accounts.create("source", 100);
        accounts.create("destination", 10);
        service.transfer("source", "destination", 25, "transfer-json");
        service.transfer("source", "destination", 25, "transfer-json");
        var payloads = jdbc.queryForList("SELECT payload FROM kafka_outbox ORDER BY sequence_id", String.class);
        assertThat(payloads).hasSize(2);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(payloads.getFirst()).get("balance").asLong()).isEqualTo(75);
        assertThat(mapper.readTree(payloads.getLast()).get("balance").asLong()).isEqualTo(35);
        assertThat(new JdbcIdempotencyStore(jdbc).find("transfer-json")).isPresent();
    }

    @Test
    void outboxFailureRollsBackBalancesReceiptAndEvents() {
        accounts.create("source", 100);
        accounts.create("destination", 10);
        var failing = new BalanceServiceImpl(accounts, new AccountLockManager(), new JdbcIdempotencyStore(jdbc),
                manager, Optional.of((transaction, primary, secondary) -> {
                    outbox.record(transaction, primary, secondary);
                    throw new IllegalStateException("Simulated outbox failure");
                }));
        assertThatThrownBy(() -> failing.transfer("source", "destination", 25, "rollback-outbox"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.getBalance("source")).isEqualTo(100);
        assertThat(service.getBalance("destination")).isEqualTo(10);
        assertThat(new JdbcIdempotencyStore(jdbc).find("rollback-outbox")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kafka_outbox", Integer.class)).isZero();
    }
}
