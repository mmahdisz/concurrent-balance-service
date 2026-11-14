package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.events.*;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.idempotency.JdbcIdempotencyStore;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.service.BalanceServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "balance.kafka.enabled=true", "balance.kafka.publisher.enabled=false",
        "balance.kafka.topic=balance.account-changed.test.v1",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.acks=all", "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.producer.properties.max.in.flight.requests.per.connection=1"
})
@EmbeddedKafka(kraft = true, partitions = 2, topics = "balance.account-changed.test.v1",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class KafkaOutboxIntegrationTest extends PostgresIntegrationTest {
    @Autowired BalanceService service;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcBalanceEventOutbox outbox;
    @Autowired KafkaOutboxPublisher publisher;
    @Autowired KafkaEventProperties properties;
    @Autowired PlatformTransactionManager manager;
    @Autowired EmbeddedKafkaBroker broker;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM kafka_outbox");
        jdbc.update("DELETE FROM processed_transactions");
        jdbc.update("DELETE FROM accounts");
        accounts.create("A", 1000);
        accounts.create("B", 100);
    }

    @Test
    void duplicateAndConflictingRequestsDoNotCreateExtraEvents() {
        service.credit("A", 50, "credit");
        service.credit("A", 50, "credit");
        assertThatThrownBy(() -> service.credit("A", 51, "credit"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(service.getBalance("A")).isEqualTo(1050);
        assertThatThrownBy(() -> service.debit("B", 101, "insufficient"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackBothBalancesAndReceiptIncludingInsertedEvents() {
        var failing = new BalanceServiceImpl(accounts, new AccountLockManager(), new JdbcIdempotencyStore(jdbc),
                manager, Optional.of((transaction, primary, secondary) -> {
                    outbox.record(transaction, primary, secondary);
                    throw new IllegalStateException("outbox write failure");
                }));
        assertThatThrownBy(() -> failing.transfer("A", "B", 20, "rollback"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.getBalance("A")).isEqualTo(1000);
        assertThat(service.getBalance("B")).isEqualTo(100);
        assertThat(new JdbcIdempotencyStore(jdbc).find("rollback")).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void brokerFailureRetainsStableEventAndStopsLaterSendsUntilRetry() throws Exception {
        service.credit("A", 10, "first");
        service.debit("A", 5, "second");
        var original = jdbc.queryForList("SELECT payload FROM kafka_outbox ORDER BY sequence_id", String.class);
        var kafka = mockKafka();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        var relay = relay(kafka, properties);
        relay.publishBatch();
        assertThat(count()).isEqualTo(2);
        verify(kafka, times(1)).send(properties.topic(), "A", original.getFirst());
        reset(kafka);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        relay.publishBatch();
        assertThat(count()).isZero();
        var ordered = inOrder(kafka);
        ordered.verify(kafka).send(properties.topic(), "A", original.get(0));
        ordered.verify(kafka).send(properties.topic(), "A", original.get(1));
    }

    @Test
    void acknowledgementFollowedByDatabaseFailureRedeliversSameEventId() {
        service.credit("A", 10, "commit-gap");
        String payload = jdbc.queryForObject("SELECT payload FROM kafka_outbox", String.class);
        Long sequence = jdbc.queryForObject("SELECT sequence_id FROM kafka_outbox", Long.class);
        var kafka = mockKafka();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var failingJdbc = spy(new JdbcTemplate(jdbc.getDataSource()));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("deletion failed"))
                .doCallRealMethod().when(failingJdbc).update("DELETE FROM kafka_outbox WHERE sequence_id = ?", sequence);
        var relay = new KafkaOutboxPublisher(failingJdbc, kafka, properties, manager, new SimpleMeterRegistry());
        relay.publishBatch();
        assertThat(count()).isEqualTo(1);
        relay.publishBatch();
        assertThat(count()).isZero();
        verify(kafka, times(2)).send(properties.topic(), "A", payload);
    }

    @Test
    void separateRelaysSerializeDeliveryWithDatabaseLock() throws Exception {
        for (int i = 0; i < 8; i++) service.credit("A", 1, "ordered-" + i);
        var payloads = jdbc.queryForList("SELECT payload FROM kafka_outbox ORDER BY sequence_id", String.class);
        var kafka = mockKafka();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var first = relay(kafka, properties);
        var second = relay(kafka, properties);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); first.publishBatch(); return null; });
            var two = pool.submit(() -> { start.await(); second.publishBatch(); return null; });
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        assertThat(count()).isZero();
        var ordered = inOrder(kafka);
        for (String payload : payloads) ordered.verify(kafka).send(properties.topic(), "A", payload);
        verify(kafka, times(8)).send(anyString(), anyString(), anyString());
    }

    @Test
    void timeoutKeepsEventPending() {
        service.credit("A", 10, "timeout");
        var kafka = mockKafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        var fast = new KafkaEventProperties(properties.topic(), 2, Duration.ofMillis(10), 2, (short) 1, 1);
        relay(kafka, fast).publishBatch();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateTransfersProduceExactlyTwoOutboxEvents() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    service.transfer("A", "B", 20, "duplicate");
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(count()).isEqualTo(2);
        assertThat(service.getBalance("A")).isEqualTo(980);
        assertThat(service.getBalance("B")).isEqualTo(120);
    }

    @Test
    void embeddedKafkaReceivesOrderedAccountKeyedVersionedJsonEvents() throws Exception {
        String transaction = UUID.randomUUID().toString();
        var consumerProperties = KafkaTestUtils.consumerProps("outbox-" + transaction, "false", broker);
        try (var consumer = new DefaultKafkaConsumerFactory<>(consumerProperties,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, properties.topic());
            service.transfer("A", "B", 20, transaction);
            service.credit("A", 5, transaction + "-credit");
            publisher.publishBatch();
            assertThat(count()).isZero();
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (received.size() < 3 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    var event = mapper.readValue(record.value(), BalanceChangedEvent.class);
                    if (event.transactionId().startsWith(transaction)) received.add(record);
                }
            }
            assertThat(received).hasSize(3);
            List<BalanceChangedEvent> aEvents = new ArrayList<>();
            for (var record : received) {
                var event = mapper.readValue(record.value(), BalanceChangedEvent.class);
                assertThat(event.schemaVersion()).isEqualTo(1);
                assertThat(event.eventId()).isNotBlank();
                assertThat(event.occurredAt()).isNotNull();
                assertThat(record.key()).isEqualTo(event.accountId());
                if (event.accountId().equals("A")) aEvents.add(event);
                else {
                    assertThat(event.balance()).isEqualTo(120);
                    assertThat(event.delta()).isEqualTo(20);
                    assertThat(event.counterpartyAccountId()).isEqualTo("A");
                }
            }
            assertThat(aEvents).extracting(BalanceChangedEvent::delta).containsExactly(-20L, 5L);
            assertThat(aEvents).extracting(BalanceChangedEvent::balance).containsExactly(980L, 985L);
        }
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM kafka_outbox", Integer.class);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockKafka() { return mock(KafkaTemplate.class); }

    private KafkaOutboxPublisher relay(KafkaTemplate<String, String> kafka, KafkaEventProperties settings) {
        return new KafkaOutboxPublisher(jdbc, kafka, settings, manager, new SimpleMeterRegistry());
    }
}
