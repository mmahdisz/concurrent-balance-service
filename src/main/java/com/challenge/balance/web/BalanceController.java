package com.challenge.balance.web;

import java.math.BigDecimal;
import com.challenge.balance.api.BalanceService;
import com.challenge.balance.repository.AccountRepository;
import com.challenge.balance.web.dto.AmountRequest;
import com.challenge.balance.web.dto.BalanceResponse;
import com.challenge.balance.web.dto.CreateAccountRequest;
import com.challenge.balance.web.dto.TransferRequest;
import jakarta.validation.Valid;
import com.challenge.balance.web.validation.ValidAccountId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/accounts")
public class BalanceController {

    private final BalanceService balanceService;
    private final AccountRepository accountRepository;


    public BalanceController(
            BalanceService balanceService,
            AccountRepository accountRepository
    ) {
        this.balanceService = balanceService;
        this.accountRepository = accountRepository;
    }


    @PostMapping
    public ResponseEntity<BalanceResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request
    ) {
        accountRepository.create(request.accountId(), request.initialBalance());
        BigDecimal balance = balanceService.getBalance(request.accountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new BalanceResponse(request.accountId(), balance));
    }


    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @PathVariable @ValidAccountId String accountId
    ) {
        BigDecimal balance = balanceService.getBalance(accountId);
        return ResponseEntity.ok(new BalanceResponse(accountId, balance));
    }


    @PostMapping("/{accountId}/credit")
    public ResponseEntity<BalanceResponse> credit(
            @PathVariable @ValidAccountId String accountId,
            @Valid @RequestBody AmountRequest request
    ) {
        balanceService.credit(accountId, request.amount(), request.transactionId());
        return ResponseEntity.ok(new BalanceResponse(accountId, balanceService.getBalance(accountId)));
    }


    @PostMapping("/{accountId}/debit")
    public ResponseEntity<BalanceResponse> debit(
            @PathVariable @ValidAccountId String accountId,
            @Valid @RequestBody AmountRequest request
    ) {
        balanceService.debit(accountId, request.amount(), request.transactionId());
        return ResponseEntity.ok(new BalanceResponse(accountId, balanceService.getBalance(accountId)));
    }


    @PostMapping("/{accountId}/transfer")
    public ResponseEntity<BalanceResponse> transfer(
            @PathVariable @ValidAccountId String accountId,
            @Valid @RequestBody TransferRequest request
    ) {
        balanceService.transfer(accountId, request.destinationAccountId(), request.amount(), request.transactionId());
        return ResponseEntity.ok(new BalanceResponse(accountId, balanceService.getBalance(accountId)));
    }

}
