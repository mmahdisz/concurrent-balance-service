package com.challenge.balance.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    public ConsoleController(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }
    public record AccountView(String id, String balance) {}
    public record ReceiptView(String transactionId, String type, String source, String destination,
                              String amount, Instant processedAt) {}
    public record Snapshot(Instant capturedAt, long accountCount, String totalBalance, long receiptCount,
                           long pendingEvents, boolean kafkaEnabled, boolean labEnabled,
                           List<AccountView> accounts, List<ReceiptView> receipts, long filteredReceiptCount) {}

    // Totals and both pages describe one committed database snapshot.
    @GetMapping("/snapshot")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int accountOffset,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int receiptOffset,
            @RequestParam(defaultValue = "") @Size(max = 255) String account) {
        String predicate = " WHERE (? = '' OR primary_account_id = ? OR secondary_account_id = ?)";
        return new Snapshot(Instant.now(),
                jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Long.class),
                jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0)::text FROM accounts", String.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions", Long.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM kafka_outbox", Long.class),
                environment.getProperty("balance.kafka.enabled", Boolean.class, false),
                environment.getProperty("balance.demo.enabled", Boolean.class, false),
                jdbc.query("SELECT id, balance::text FROM accounts ORDER BY id LIMIT 20 OFFSET ?",
                        (rs, row) -> new AccountView(rs.getString(1), rs.getString(2)), accountOffset),
                jdbc.query("SELECT * FROM processed_transactions" + predicate
                                + " ORDER BY processed_at DESC, transaction_id DESC LIMIT 20 OFFSET ?",
                        (rs, row) -> new ReceiptView(rs.getString("transaction_id"),
                                rs.getString("operation_type"), rs.getString("primary_account_id"),
                                rs.getString("secondary_account_id"), rs.getString("amount"),
                                rs.getObject("processed_at", OffsetDateTime.class).toInstant()),
                        account, account, account, receiptOffset),
                jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions" + predicate,
                        Long.class, account, account, account));
    }
}
