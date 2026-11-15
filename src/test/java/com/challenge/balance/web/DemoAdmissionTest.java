package com.challenge.balance.web;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoAdmissionTest {
    @Test
    void rejectsOverlappingRunsAndExposesSetupFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var accounts = mock(AccountRepository.class);
        when(accounts.create(anyString(), anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Simulated database failure");
        });
        var controller = new DemoController(mock(BalanceService.class), accounts, mock(JdbcTemplate.class));
        try {
            var request = new DemoController.Request(DemoController.Scenario.BURST, 10, 4);
            var run = controller.start(request);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> controller.start(request)).isInstanceOfSatisfying(
                    ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (controller.get(run.id()).state().equals("RUNNING") && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertThat(controller.get(run.id()).state()).isEqualTo("FAILED");
            assertThat(controller.get(run.id()).message()).contains("partial changes");
        } finally {
            release.countDown();
            controller.close();
        }
    }
}
