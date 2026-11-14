package com.challenge.balance.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "balance.kafka", name = "enabled", havingValue = "true")
public class KafkaOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaEventProperties properties;
    private final TransactionTemplate transactions;
    private final Counter published;
    private final Counter failures;

    public KafkaOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                KafkaEventProperties properties, PlatformTransactionManager manager,
                                MeterRegistry registry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.published = registry.counter("balance.kafka.outbox.published");
        this.failures = registry.counter("balance.kafka.outbox.failures");
        registry.gauge("balance.kafka.outbox.pending", this,
                publisher -> publisher.jdbc.queryForObject("SELECT COUNT(*) FROM kafka_outbox", Long.class));
    }

    /** Stop at the first failure; a later event must not overtake an unsent event. */
    public void publishBatch() {
        for (int i = 0; i < properties.batchSize() && !Thread.currentThread().isInterrupted(); i++) {
            try {
                if (!Boolean.TRUE.equals(transactions.execute(status -> publishNext()))) {
                    return;
                }
                published.increment();
            } catch (RuntimeException ex) {
                failures.increment();
                log.warn("Kafka outbox delivery failed; pending events will be retried", ex);
                return;
            }
        }
    }

    private boolean publishNext() {
        // Serializes relays across application instances without locking balance/account rows.
        // Keep this lock until acknowledgement AND deletion commit to preserve account ordering.
        jdbc.queryForObject("SELECT id FROM kafka_outbox_lock WHERE id = 1 FOR UPDATE", Integer.class);
        var pending = jdbc.query("""
                SELECT sequence_id, account_id, payload FROM kafka_outbox ORDER BY sequence_id LIMIT 1
                """, (rs, row) -> new PendingEvent(rs.getLong(1), rs.getString(2), rs.getString(3)));
        if (pending.isEmpty()) {
            return false;
        }
        var event = pending.getFirst();
        try {
            kafka.send(properties.topic(), event.accountId(), event.payload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka outbox delivery interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka did not acknowledge outbox event " + event.sequenceId(), ex);
        }
        // Crash after Kafka ack but before commit can redeliver the same eventId: at-least-once.
        jdbc.update("DELETE FROM kafka_outbox WHERE sequence_id = ?", event.sequenceId());
        return true;
    }

    private record PendingEvent(long sequenceId, String accountId, String payload) { }
}
