package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletProblemException;
import com.minipay.wallet.infrastructure.persistence.WalletRepository;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.LedgerEntryRow;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.LedgerTransactionRow;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.OpsAccountRow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management/wallet")
public class OpsWalletController {
    private final WalletRepository repository;

    public OpsWalletController(WalletRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/accounts/{accountId}")
    @Transactional(readOnly = true)
    public OpsAccountRow account(@PathVariable UUID accountId) {
        return repository.findOpsAccount(accountId)
                .orElseThrow(() -> new WalletProblemException(
                        "WALLET_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    @GetMapping("/ledger-transactions")
    @Transactional(readOnly = true)
    public LedgerPage ledger(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new LedgerPage(
                repository.listLedgerTransactions(page, size),
                page,
                size,
                repository.countLedgerTransactions());
    }

    @GetMapping("/ledger-transactions/{transactionId}")
    @Transactional(readOnly = true)
    public LedgerDetail ledgerDetail(@PathVariable UUID transactionId) {
        LedgerTransactionRow transaction = repository.findLedgerTransaction(transactionId)
                .orElseThrow(() -> new WalletProblemException(
                        "LEDGER_TRANSACTION_NOT_FOUND", HttpStatus.NOT_FOUND));
        return new LedgerDetail(
                transaction,
                repository.listLedgerEntries(transactionId));
    }

    public record LedgerPage(
            List<LedgerTransactionRow> items,
            int page,
            int size,
            long total) {
    }

    public record LedgerDetail(
            LedgerTransactionRow transaction,
            List<LedgerEntryRow> entries) {
    }
}
