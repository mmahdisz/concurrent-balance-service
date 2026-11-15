package com.challenge.balance.web;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.repository.AccountRepository;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/demo/runs")
@ConditionalOnProperty(name = "balance.demo.enabled", havingValue = "true")
public class DemoController {
    private static final Logger log = LoggerFactory.getLogger(DemoController.class);
    public enum Scenario { COMPETING, DUPLICATE, OPPOSING, BURST }
    public record Request(@NotNull Scenario scenario, @Min(2) @Max(100) int requests,
                          @Min(2) @Max(16) int concurrency) {}
    public record Attempt(int index, String transactionId, String source, String destination,
                          long amount, String outcome, double durationMs) {}
    public record Result(String id, String state, Scenario scenario, Instant startedAt,
                         int requested, int acknowledged, int rejected, int errors,
                         long committedReceipts, Map<String, String> balances,
                         boolean conserved, boolean nonnegative, boolean expectedEffect,
                         double durationMs, double p95Ms, List<Attempt> attempts, String message) {}
    private final BalanceService service;
    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final Map<String, Result> runs = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    public DemoController(BalanceService service, AccountRepository accounts, JdbcTemplate jdbc) {
        this.service = service;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result start(@Valid @RequestBody Request request) {
        if (!busy.compareAndSet(false, true))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A demonstration is already running.");
        String id = UUID.randomUUID().toString();
        int count = request.scenario() == Scenario.COMPETING ? 2 : request.requests();
        Result initial = new Result(id, "RUNNING", request.scenario(), Instant.now(), count,
                0, 0, 0, 0, Map.of(), false, false, false, 0, 0, List.of(), "Running on isolated accounts");
        if (runs.size() >= 20) runs.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().startedAt())).ifPresent(e -> runs.remove(e.getKey()));
        runs.put(id, initial);
        coordinator.submit(() -> execute(initial, request));
        return initial;
    }

    @GetMapping("/{id}")
    public Result get(@PathVariable String id) {
        Result result = runs.get(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found or expired.");
        return result;
    }

    private void execute(Result initial, Request request) {
        long started = System.nanoTime();
        String prefix = "lab-" + initial.id() + "-";
        String a = prefix + "A", b = prefix + "B";
        boolean competing = request.scenario() == Scenario.COMPETING;
        long total = competing ? 100 : 2000;
        try (ExecutorService workers = Executors.newFixedThreadPool(Math.min(request.concurrency(), initial.requested()))) {
            accounts.create(a, competing ? 100 : 1000);
            accounts.create(b, competing ? 0 : 1000);
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int i = 0; i < initial.requested(); i++) {
                int index = i;
                futures.add(workers.submit(() -> {
                    gate.await();
                    boolean reverse = (request.scenario() == Scenario.OPPOSING
                            || request.scenario() == Scenario.BURST) && index % 2 == 1;
                    String source = reverse ? b : a, destination = reverse ? a : b;
                    String tx = prefix + (request.scenario() == Scenario.DUPLICATE ? "same" : index);
                    long amount = competing ? 80 : request.scenario() == Scenario.DUPLICATE ? 10 : 1;
                    long begin = System.nanoTime();
                    String outcome;
                    try {
                        service.transfer(source, destination, amount, tx);
                        outcome = "ACKNOWLEDGED";
                    } catch (InsufficientFundsException ex) {
                        outcome = "INSUFFICIENT_FUNDS";
                    } catch (RuntimeException ex) {
                        log.warn("Demo transfer {} failed", tx, ex);
                        outcome = "TECHNICAL_ERROR";
                    }
                    return new Attempt(index, tx, source, destination, amount, outcome,
                            (System.nanoTime() - begin) / 1_000_000.0);
                }));
            }
            gate.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) attempts.add(future.get());
            long balanceA = service.getBalance(a), balanceB = service.getBalance(b);
            long receipts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_transactions WHERE primary_account_id IN (?, ?)",
                    Long.class, a, b);
            int acknowledged = (int) attempts.stream().filter(t -> t.outcome().equals("ACKNOWLEDGED")).count();
            int rejected = (int) attempts.stream().filter(t -> t.outcome().equals("INSUFFICIENT_FUNDS")).count();
            int errors = attempts.size() - acknowledged - rejected;
            long expectedReceipts = competing || request.scenario() == Scenario.DUPLICATE ? 1 : initial.requested();
            long expectedA = competing ? 20 : request.scenario() == Scenario.DUPLICATE ? 990
                    : 1000 - initial.requested() % 2;
            boolean expectedOutcomes = competing ? acknowledged == 1 && rejected == 1
                    : acknowledged == initial.requested() && rejected == 0;
            List<Double> durations = attempts.stream().map(Attempt::durationMs).sorted().toList();
            runs.put(initial.id(), new Result(initial.id(), "COMPLETED", request.scenario(),
                    initial.startedAt(), initial.requested(), acknowledged, rejected, errors, receipts,
                    Map.of(a, Long.toString(balanceA), b, Long.toString(balanceB)),
                    balanceA + balanceB == total, balanceA >= 0 && balanceB >= 0,
                    errors == 0 && expectedOutcomes && receipts == expectedReceipts && balanceA == expectedA
                            && balanceB == total - expectedA,
                    (System.nanoTime() - started) / 1_000_000.0,
                    durations.get((int) Math.ceil(durations.size() * .95) - 1), List.copyOf(attempts),
                    "Acknowledgements include idempotent replays. Receipts count unique committed effects."));
        } catch (Exception ex) {
            log.warn("Demo run {} could not finish", initial.id(), ex);
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            runs.put(initial.id(), new Result(initial.id(), "FAILED", request.scenario(), initial.startedAt(),
                    initial.requested(), 0, 0, 0, 0, Map.of(), false, false, false,
                    (System.nanoTime() - started) / 1_000_000.0, 0, List.of(),
                    "Run could not finish. Inspect durable receipts before retrying; partial changes may exist."));
        } finally {
            busy.set(false);
        }
    }
    @PreDestroy
    public void close() { coordinator.shutdownNow(); }
}
