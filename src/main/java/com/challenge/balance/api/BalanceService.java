package com.challenge.balance.api;

import java.math.BigDecimal;
public interface BalanceService {

    void credit(String accountId, BigDecimal amount, String transactionId);

    void debit(String accountId, BigDecimal amount, String transactionId);

    void transfer(String sourceAccountId, String destinationAccountId, BigDecimal amount, String transactionId);

    BigDecimal getBalance(String accountId);
}
