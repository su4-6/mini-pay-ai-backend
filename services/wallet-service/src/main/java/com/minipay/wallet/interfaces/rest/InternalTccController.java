package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletTransferTccService;
import com.minipay.wallet.application.service.WalletTransferTccService.TransferOutcome;
import com.minipay.wallet.infrastructure.tcc.CreditAccountTccAction;
import com.minipay.wallet.infrastructure.tcc.DebitAccountTccAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/tcc")
public class InternalTccController {
    private final WalletTransferTccService transfers;
    private final DebitAccountTccAction debitAction;
    private final CreditAccountTccAction creditAction;

    public InternalTccController(
            WalletTransferTccService transfers,
            DebitAccountTccAction debitAction,
            CreditAccountTccAction creditAction) {
        this.transfers = transfers;
        this.debitAction = debitAction;
        this.creditAction = creditAction;
    }

    @PostMapping("/debits/try")
    public BranchResult tryDebit(@Valid @RequestBody TryBranchRequest request) {
        return new BranchResult(debitAction.tryDebit(
                null,
                request.businessNo(),
                request.source(),
                request.ownerId().toString(),
                request.accountId().toString(),
                request.counterpartyOwnerId().toString(),
                request.amountCent(),
                request.annualLimitMode()));
    }

    @PostMapping("/credits/try")
    public BranchResult tryCredit(@Valid @RequestBody TryBranchRequest request) {
        return new BranchResult(creditAction.tryCredit(
                null,
                request.businessNo(),
                request.source(),
                request.ownerId().toString(),
                request.accountId().toString(),
                request.counterpartyOwnerId().toString(),
                request.amountCent()));
    }

    @PostMapping("/transfers/outcomes")
    public TransferOutcome outcome(@Valid @RequestBody TransferOutcomeRequest request) {
        return transfers.outcome(
                request.businessNo(),
                request.payerAccountId(),
                request.receiverAccountId());
    }

    public record TryBranchRequest(
            @NotBlank String businessNo,
            String source,
            @NotNull UUID ownerId,
            @NotNull UUID accountId,
            @NotNull UUID counterpartyOwnerId,
            @Min(1) long amountCent,
            String annualLimitMode) {
    }

    public record TransferOutcomeRequest(
            @NotBlank String businessNo,
            @NotNull UUID payerAccountId,
            @NotNull UUID receiverAccountId) {
    }

    public record BranchResult(boolean accepted) {
    }
}
