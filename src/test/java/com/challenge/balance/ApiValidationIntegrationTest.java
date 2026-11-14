package com.challenge.balance;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiValidationIntegrationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM processed_transactions");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("INSERT INTO accounts (id, balance) VALUES ('A', 1000), ('B', 200)");
    }

    @ParameterizedTest(name = "{index}: {0} rejects invalid {2}")
    @MethodSource("invalidRequests")
    void invalidFieldsReturnProblemDetailsWithoutChangingState(String url, Map<String, Object> body,
                                                               String field) throws Exception {
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value(url))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty());
        assertUnchanged();
    }

    static Stream<Arguments> invalidRequests() {
        List<Arguments> cases = new ArrayList<>();
        Map<String, Object> account = Map.of("accountId", "C", "initialBalance", 0);
        addInvalidIds(cases, "/accounts", account, "accountId");
        for (Object value : Arrays.asList(null, -1)) {
            cases.add(Arguments.of("/accounts", with(account, "initialBalance", value), "initialBalance"));
        }
        cases.add(Arguments.of("/accounts", without(account, "initialBalance"), "initialBalance"));
        for (String operation : List.of("credit", "debit", "transfer")) {
            String url = "/accounts/A/" + operation;
            Map<String, Object> body = new HashMap<>(Map.of("amount", 1, "transactionId", "tx"));
            if (operation.equals("transfer")) {
                body.put("destinationAccountId", "B");
                addInvalidIds(cases, url, body, "destinationAccountId");
            }
            addInvalidIds(cases, url, body, "transactionId");
            for (Object value : Arrays.asList(null, 0, -1)) {
                cases.add(Arguments.of(url, with(body, "amount", value), "amount"));
            }
            cases.add(Arguments.of(url, without(body, "amount"), "amount"));
        }
        return cases.stream();
    }

    private static void addInvalidIds(List<Arguments> cases, String url, Map<String, Object> body, String field) {
        for (Object value : Arrays.asList(null, "", "   ", "x".repeat(256))) {
            cases.add(Arguments.of(url, with(body, field, value), field));
        }
        cases.add(Arguments.of(url, without(body, field), field));
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> changed = new HashMap<>(body);
        changed.put(field, value);
        return changed;
    }

    private static Map<String, Object> without(Map<String, Object> body, String field) {
        Map<String, Object> changed = new HashMap<>(body);
        changed.remove(field);
        return changed;
    }

    @Test
    void validationResponseReportsEveryInvalidField() throws Exception {
        mvc.perform(post("/accounts/A/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0,\"transactionId\":\"\",\"destinationAccountId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").isArray())
                .andExpect(jsonPath("$.fieldErrors.transactionId").isArray())
                .andExpect(jsonPath("$.fieldErrors.destinationAccountId").isArray());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidAccountPathReturnsValidationError(String accountId) throws Exception {
        mvc.perform(get("/accounts/{accountId}/balance", accountId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.accountId").isNotEmpty());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "{\"amount\":\"abc\",\"transactionId\":\"tx\"}",
            "{\"amount\":true,\"transactionId\":\"tx\"}"})
    void malformedBodiesReturnSafeBadRequest(String body) throws Exception {
        mvc.perform(post("/accounts/A/credit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.detail").value(
                        "Request body must contain valid JSON with the expected field types."));
        assertUnchanged();
    }

    @Test
    void validBoundaryValuesAreAccepted() throws Exception {
        String id = "x".repeat(255);
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("accountId", id, "initialBalance", 0))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value(0));
        mvc.perform(post("/accounts/{accountId}/credit", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("amount", 1, "transactionId", id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(1));
        mvc.perform(get("/accounts/{accountId}/balance", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(id));
    }

    @Test
    void domainExceptionsUseCorrespondingStatusesAndCodes() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"A\",\"initialBalance\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ACCOUNT_ALREADY_EXISTS"));
        mvc.perform(get("/accounts/missing/balance"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(post("/accounts/A/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationAccountId\":\"A\",\"amount\":1,\"transactionId\":\"tx\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("SAME_ACCOUNT_TRANSFER"));
        assertUnchanged();
    }

    @Test
    void frameworkErrorsKeepHttpStatusesAndHeaders() throws Exception {
        mvc.perform(put("/accounts/A/balance"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.status").value(405)).andExpect(jsonPath("$.error").value("HTTP_405"));
        mvc.perform(post("/accounts/A/credit").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        assertUnchanged();
    }

    @Test
    void databaseFailureReturnsSafeInternalErrorAndRollsBack() throws Exception {
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = 'B'", Long.MAX_VALUE);
        jdbc.execute("ALTER TABLE accounts ADD CONSTRAINT test_balance_limit CHECK (balance <= 9223372036854775807)");
        try {
            mvc.perform(post("/accounts/A/transfer").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"destinationAccountId\":\"B\",\"amount\":1,\"transactionId\":\"tx\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                    .andExpect(jsonPath("$.fieldErrors").isEmpty())
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist());
            assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = 'A'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.valueOf(1000));
            assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = 'B'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.valueOf(Long.MAX_VALUE));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE accounts DROP CONSTRAINT test_balance_limit");
        }
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = 'A'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = 'B'", BigDecimal.class)).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_transactions", Integer.class)).isZero();
    }
}
