package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.domain.TransactionRecord;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in protocol/expiry verification against a real Redis server, with isolated keys and H2. */
@SpringBootTest(properties = {
        "idempotency.redis.enabled=true", "idempotency.redis.ttl=2s",
        "spring.data.redis.connect-timeout=250ms", "spring.data.redis.timeout=250ms",
        "spring.data.redis.repositories.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:redis-live-tests;DB_CLOSE_DELAY=-1"
})
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
class RedisLiveIntegrationTest {

    private static final String PREFIX = "balance-service:test:" + UUID.randomUUID() + ":v1:";
    @Autowired private BalanceService service;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("idempotency.redis.key-prefix", () -> PREFIX);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM processed_transactions");
        jdbc.update("DELETE FROM accounts");
        accounts.create("A", 1000);
        accounts.create("B", 200);
    }

    @AfterEach
    void removeOwnKeys() throws Exception {
        redis.delete(List.of(key("live-transfer"), key("live-expiry")));
    }

    @Test
    void realRedisStoresJsonAndRetainsRequestParameters() throws Exception {
        service.transfer("A", "B", 300, "live-transfer");
        String json = redis.opsForValue().get(key("live-transfer"));
        assertThat(json).isNotNull();
        TransactionRecord receipt = mapper.readValue(json, TransactionRecord.class);
        assertThat(receipt.matches(com.challenge.balance.domain.OperationType.TRANSFER, "A", "B", 300)).isTrue();

        service.transfer("A", "B", 300, "live-transfer");
        assertThatThrownBy(() -> service.transfer("A", "B", 301, "live-transfer"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(service.getBalance("A")).isEqualTo(700);
        assertThat(service.getBalance("B")).isEqualTo(500);
    }

    @Test
    void realRedisExpiryStillLeavesDurableDuplicateProtection() throws Exception {
        service.credit("A", 100, "live-expiry");
        String key = key("live-expiry");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(redis.hasKey(key)).isFalse();

        service.credit("A", 100, "live-expiry");

        assertThat(service.getBalance("A")).isEqualTo(1100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions", Integer.class)).isEqualTo(1);
        assertThat(redis.hasKey(key)).isTrue();
    }

    private static String key(String transactionId) throws Exception {
        return PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(transactionId.getBytes(StandardCharsets.UTF_8)));
    }
}
