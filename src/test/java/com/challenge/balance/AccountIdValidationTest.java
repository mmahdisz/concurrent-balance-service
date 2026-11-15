package com.challenge.balance;

import com.challenge.balance.api.BalanceService;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.web.BalanceController;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BalanceController.class)
class AccountIdValidationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean BalanceService service;
    @MockitoBean AccountRepository accounts;

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "a\\b", ".", "..", "a%2Fb", "a;b", "a b", "a\u0000b"})
    void rejectsUnaddressableAccountIdsBeforeChangingState(String id) throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("accountId", id, "initialBalance", 100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.accountId").isNotEmpty());
        mvc.perform(post("/accounts/A/transfer").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("destinationAccountId", id,
                                "amount", 1, "transactionId", "tx"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.destinationAccountId").isNotEmpty());
        verifyNoInteractions(service, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a%2Fb"})
    void rejectsInvalidPathIdsBeforeCallingService(String id) throws Exception {
        mvc.perform(get("/accounts/{accountId}/balance", id))
                .andExpect(status().isBadRequest());
        for (String operation : new String[]{"credit", "debit", "transfer"}) {
            mvc.perform(post("/accounts/{accountId}/" + operation, id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":1,\"transactionId\":\"tx\",\"destinationAccountId\":\"B\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "account-1", "a.b_c~d", "0123456789"})
    void acceptsAddressableIdsForCreationAndReads(String id) throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("accountId", id, "initialBalance", 0))))
                .andExpect(status().isCreated());
        mvc.perform(get("/accounts/{accountId}/balance", id))
                .andExpect(status().isOk());
    }
}
