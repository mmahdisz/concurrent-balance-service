package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.web.DemoController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "balance.demo.enabled=true")
@AutoConfigureMockMvc
class ConsoleIntegrationTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired BalanceService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void snapshotPreservesBigintAndFiltersBothTransferSides() throws Exception {
        String prefix = "console-" + UUID.randomUUID();
        accounts.create(prefix + "A", new BigDecimal("9007199254740993"));
        accounts.create(prefix + "B", BigDecimal.ZERO);
        service.transfer(prefix + "A", prefix + "B", BigDecimal.valueOf(7), prefix + "tx");
        mvc.perform(get("/api/console/snapshot").param("account", prefix + "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filteredReceiptCount").value(1))
                .andExpect(jsonPath("$.receipts[0].amount").value("7"))
                .andExpect(jsonPath("$.receipts[0].transactionId").value(prefix + "tx"))
                .andExpect(jsonPath("$.labEnabled").value(true));
        var snapshot = json.readTree(mvc.perform(get("/api/console/snapshot"))
                .andReturn().getResponse().getContentAsString());
        assertThat(snapshot.get("totalBalance").isTextual()).isTrue();
        assertThat(snapshot.get("totalBalance").asText()).isEqualTo(
                jdbc.queryForObject("SELECT SUM(balance)::text FROM accounts", String.class));
        mvc.perform(get("/api/console/snapshot").param("accountOffset", "1000000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accounts").isEmpty());
        mvc.perform(get("/api/console/snapshot").param("receiptOffset", "-1")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(DemoController.Scenario.class)
    void experimentsProveUniqueEffectsAndConservation(DemoController.Scenario scenario) throws Exception {
        var response = mvc.perform(post("/api/demo/runs").contentType("application/json")
                        .content(json.writeValueAsString(new DemoController.Request(scenario, 11, 4))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).get("id").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        DemoController.Result result;
        do {
            Thread.sleep(25);
            result = json.readValue(mvc.perform(get("/api/demo/runs/" + id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(), DemoController.Result.class);
        } while (result.state().equals("RUNNING") && System.nanoTime() < deadline);
        assertThat(result.state()).isEqualTo("COMPLETED");
        assertThat(result.conserved()).isTrue();
        assertThat(result.nonnegative()).isTrue();
        assertThat(result.expectedEffect()).isTrue();
        assertThat(result.errors()).isZero();
        assertThat(result.committedReceipts()).isEqualTo(
                scenario == DemoController.Scenario.COMPETING || scenario == DemoController.Scenario.DUPLICATE ? 1 : 11);
        if (scenario == DemoController.Scenario.COMPETING) {
            assertThat(result.acknowledged()).isEqualTo(1);
            assertThat(result.rejected()).isEqualTo(1);
        }
        if (scenario == DemoController.Scenario.DUPLICATE) assertThat(result.acknowledged()).isEqualTo(11);
    }

    @Test
    void rejectsUnboundedWorkAndUnknownRun() throws Exception {
        mvc.perform(post("/api/demo/runs").contentType("application/json")
                .content("{\"scenario\":\"BURST\",\"requests\":101,\"concurrency\":17}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/demo/runs/missing")).andExpect(status().isNotFound());
    }
}
