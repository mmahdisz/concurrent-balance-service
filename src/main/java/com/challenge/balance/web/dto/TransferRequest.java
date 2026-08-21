package com.challenge.balance.web.dto;

public record TransferRequest(String destinationAccountId, long amount, String transactionId) {
}
