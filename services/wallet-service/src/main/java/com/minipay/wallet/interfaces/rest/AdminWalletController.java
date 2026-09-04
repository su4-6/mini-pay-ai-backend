package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletApplicationService;
import com.minipay.wallet.application.service.WalletProblemException;
import com.minipay.wallet.domain.model.BillPage;
import com.minipay.wallet.domain.model.BillQuery;
import com.minipay.wallet.infrastructure.persistence.WalletRepository;
import com.minipay.wallet.infrastructure.persistence.WalletRepository.OpsAccountRow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** System administrator read-only wallet and immutable ledger views. */
@Validated @RestController @RequestMapping("/api/v1/admin")
public class AdminWalletController {
    private final WalletRepository repository; private final WalletApplicationService wallets;
    public AdminWalletController(WalletRepository repository,WalletApplicationService wallets){this.repository=repository;this.wallets=wallets;}
    @GetMapping("/wallets") @Transactional(readOnly=true)
    public WalletPage list(@RequestParam(defaultValue="1")@Min(1)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size,@RequestParam(required=false)UUID ownerId,@RequestParam(required=false)String status){return new WalletPage(repository.listOpsAccounts(page,size,ownerId,status),page,size,repository.countOpsAccounts(ownerId,status));}
    @GetMapping("/wallets/{accountId}") @Transactional(readOnly=true)
    public OpsAccountRow detail(@PathVariable UUID accountId){return repository.findOpsAccount(accountId).orElseThrow(()->new WalletProblemException("WALLET_ACCOUNT_NOT_FOUND",HttpStatus.NOT_FOUND));}
    @GetMapping("/wallets/{accountId}/bills") @Transactional(readOnly=true)
    public BillPage bills(@PathVariable UUID accountId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){OpsAccountRow account=detail(accountId);return wallets.listBills(account.ownerId(),new BillQuery(null,null,null,null,null,null,page,Math.max(1,Math.min(100,size))));}
    @GetMapping("/ledger-transactions") public Object ledger(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return new LedgerPage(repository.listLedgerTransactions(page,Math.max(1,Math.min(100,size))),page,size,repository.countLedgerTransactions());}
    @GetMapping("/ledger-transactions/{id}") public Object ledgerDetail(@PathVariable UUID id){var tx=repository.findLedgerTransaction(id).orElseThrow(()->new WalletProblemException("LEDGER_TRANSACTION_NOT_FOUND",HttpStatus.NOT_FOUND));return new LedgerDetail(tx,repository.listLedgerEntries(id));}
    public record WalletPage(List<OpsAccountRow> items,int page,int size,long total){}
    public record LedgerPage(List<WalletRepository.LedgerTransactionRow> items,int page,int size,long total){}
    public record LedgerDetail(WalletRepository.LedgerTransactionRow transaction,List<WalletRepository.LedgerEntryRow> entries){}
}
