package com.challenge.balance.service;

import com.challenge.balance.concurrency.AccountLockManager;
import com.challenge.balance.domain.Account;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.idempotency.InMemoryIdempotencyStore;
import com.challenge.balance.repository.InMemoryAccountRepository;
import com.challenge.balance.web.BalanceController;
import com.challenge.balance.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DecimalBalanceTest {
    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final BalanceServiceImpl service = new BalanceServiceImpl(accounts,
            new AccountLockManager(), new InMemoryIdempotencyStore());

    @Test
    void decimalArithmeticAndTransferPreserveExactValues() {
        accounts.create("A", BigDecimal.ZERO);
        accounts.create("B", BigDecimal.ZERO);
        service.credit("A", new BigDecimal("0.1"), "one");
        service.credit("A", new BigDecimal("0.2"), "two");
        service.transfer("A", "B", new BigDecimal("0.125"), "transfer");
        assertThat(service.getBalance("A")).isEqualByComparingTo("0.175");
        assertThat(service.getBalance("B")).isEqualByComparingTo("0.125");
        service.debit("A", new BigDecimal("0.175"), "debit");
        assertThat(service.getBalance("A")).isZero();
    }

    @Test
    void differentScalesDoNotCauseIdempotencyConflicts() {
        accounts.create("A", BigDecimal.ZERO);
        service.credit("A", new BigDecimal("1.0"), "same");
        service.credit("A", new BigDecimal("1.00"), "same");
        assertThat(service.getBalance("A")).isEqualByComparingTo("1");
    }

    @Test
    void balancesCanExceedLongRangeWithoutLosingFractionalDigits() {
        accounts.create("A", new BigDecimal("9223372036854775808.123456789"));
        service.credit("A", new BigDecimal("0.000000001"), "large");
        assertThat(service.getBalance("A")).isEqualByComparingTo("9223372036854775808.123456790");
    }

    @Test
    void modelRejectsInvalidAmountsAndOverdraftsWithoutMutation() {
        Account account = new Account("A", new BigDecimal("0.10"));
        assertThatThrownBy(() -> account.credit(null)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.credit(new BigDecimal("-0.01"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.debit(BigDecimal.ZERO)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.debit(new BigDecimal("0.11"))).isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("0.10");
        assertThatThrownBy(() -> new Account("B", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentFractionalCreditsAreExact() throws Exception {
        accounts.create("A", BigDecimal.ZERO);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 1000; i++) {
                String tx = "fraction-" + i;
                futures.add(pool.submit(() -> service.credit("A", new BigDecimal("0.01"), tx)));
            }
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.getBalance("A")).isEqualByComparingTo("10.00");
    }

    @Test
    void apiReadsAndWritesExactDecimalJsonNumbers() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BalanceController(service, accounts))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"A\",\"initialBalance\":0.123456789}"))
                .andExpect(status().isCreated());
        var response = mvc.perform(post("/accounts/A/credit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.000000001,\"transactionId\":\"decimal\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Read the numeric token directly as BigDecimal; a double would lose precision.
        try (var parser = new ObjectMapper().getFactory().createParser(response)) {
            while (parser.nextToken() != null) {
                if ("balance".equals(parser.currentName())) {
                    parser.nextToken();
                    assertThat(parser.getDecimalValue()).isEqualByComparingTo("0.123456790");
                    return;
                }
            }
        }
        throw new AssertionError("Missing balance in response");
    }
}
