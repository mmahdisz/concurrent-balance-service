package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the application context wires up correctly - all beans
 * (repository, lock manager, idempotency store, service, controller,
 * exception handler) construct without a circular-dependency or
 * missing-bean error.
 */
@SpringBootTest
class BalanceServiceApplicationTests extends PostgresIntegrationTest {

    @Autowired
    private BalanceService balanceService;

    @Test
    void contextLoads() {
        assertThat(balanceService).isNotNull();
    }
}
