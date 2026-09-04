package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletApplicationService;
import com.minipay.wallet.application.service.WalletApplicationService.PostingResult;
import com.minipay.wallet.application.service.WalletApplicationService.ResolvedAccount;
import com.minipay.wallet.domain.model.WalletBill;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class InternalWalletController {
    private final WalletApplicationService wallets;

    public InternalWalletController(WalletApplicationService wallets) {
        this.wallets = wallets;
    }

    @PostMapping("/wallet-accounts/resolve")
    public ResolvedAccount resolve(@Valid @RequestBody ResolveAccountRequest request) {
        return wallets.resolve(request.ownerId());
    }

    @PostMapping("/wallet-postings/opening-grants")
    public ResponseEntity<?> open(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OpeningGrantRequest request) {
        requireEventKey(idempotencyKey, request.eventId());
        PostingResult result = wallets.openWallet(request.eventId(), request.userId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(wallets.getWallet(request.userId()));
    }

    @PostMapping("/wallet-postings/recharges")
    public ResponseEntity<?> recharge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RechargePostingRequest request) {
        requireEventKey(idempotencyKey, request.eventId());
        PostingResult result = wallets.postRecharge(
                request.eventId(),
                request.rechargeId(),
                request.rechargeNo(),
                request.userId(),
                request.amountCent());
        WalletBill bill = result.bill();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(bill);
    }

    @PostMapping("/wallet-postings/withdrawals")
    public ResponseEntity<?> withdrawal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawalPostingRequest request) {
        if (!("withdrawal:" + request.withdrawalId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must bind the withdrawal id");
        }
        PostingResult result = wallets.postWithdrawal(
                request.withdrawalId(),
                request.withdrawalNo(),
                request.userId(),
                request.amountCent());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.bill());
    }

    @PostMapping("/wallet-postings/withdrawals/complete")
    public WalletBill completeWithdrawal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawalCompletionRequest request) {
        if (!("withdrawal-complete:" + request.withdrawalId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must bind the withdrawal completion");
        }
        return wallets.completeWithdrawal(request.userId(), request.withdrawalNo());
    }

    @PostMapping("/wallet-postings/withdrawals/reverse")
    public WalletBill reverseWithdrawal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawalReversalRequest request) {
        if (!("withdrawal-reverse:" + request.withdrawalId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must bind the withdrawal reversal");
        }
        return wallets.reverseWithdrawal(
                request.withdrawalId(),
                request.userId(),
                request.withdrawalNo(),
                request.amountCent(),
                request.failureCode());
    }

    @PostMapping("/wallet-postings/payments")
    public ResponseEntity<?> payment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentPostingRequest request) {
        if (!("payment:" + request.paymentOrderId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key must bind the payment id");
        }
        PostingResult result = wallets.postWalletPayment(
                request.paymentOrderId(),
                request.paymentOrderNo(),
                request.userId(),
                request.amountCent(),
                request.subject(),
                request.annualLimitMode());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.bill());
    }

    @PostMapping("/wallet-postings/refunds")
    public ResponseEntity<?> refund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundPostingRequest request) {
        if (!("refund:" + request.refundId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key must bind the refund id");
        }
        PostingResult result = wallets.postRefund(
                request.refundId(),
                request.refundNo(),
                request.userId(),
                request.amountCent(),
                request.originalPaymentNo());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.bill());
    }

    @PostMapping("/wallet-postings/merchant-payments")
    public ResponseEntity<?> merchantPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MerchantPaymentPostingRequest request) {
        if (!("merchant-payment:" + request.paymentOrderId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must bind the merchant payment id");
        }
        PostingResult result = wallets.postMerchantPayment(
                request.paymentOrderId(), request.paymentOrderNo(), request.payerUserId(),
                request.merchantOwnerUserId(), request.amountCent(), request.subject(),
                request.annualLimitMode(), request.fundingSource(), request.merchantName());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.bill());
    }

    @PostMapping("/wallet-postings/merchant-refunds")
    public ResponseEntity<?> merchantRefund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MerchantRefundPostingRequest request) {
        if (!("merchant-refund:" + request.refundId()).equals(idempotencyKey)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must bind the merchant refund id");
        }
        PostingResult result = wallets.postMerchantRefund(
                request.refundId(), request.refundNo(), request.payerUserId(),
                request.merchantOwnerUserId(), request.amountCent(),
                request.originalPaymentNo(), request.merchantName());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.bill());
    }

    private void requireEventKey(String idempotencyKey, UUID eventId) {
        if (!("event:" + eventId).equals(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key must bind the event id");
        }
    }

    public record ResolveAccountRequest(@NotNull UUID ownerId) {
    }

    public record OpeningGrantRequest(@NotNull UUID eventId, @NotNull UUID userId) {
    }

    public record RechargePostingRequest(
            @NotNull UUID eventId,
            @NotNull UUID rechargeId,
            @NotBlank String rechargeNo,
            @NotNull UUID userId,
            @Min(1) @Max(1_000_000) long amountCent) {
    }

    public record WithdrawalPostingRequest(
            @NotNull UUID withdrawalId,
            @NotBlank String withdrawalNo,
            @NotNull UUID userId,
            @Min(1) @Max(1_000_000) long amountCent) {
    }

    public record WithdrawalCompletionRequest(
            @NotNull UUID withdrawalId,
            @NotBlank String withdrawalNo,
            @NotNull UUID userId) {
    }

    public record WithdrawalReversalRequest(
            @NotNull UUID withdrawalId,
            @NotBlank String withdrawalNo,
            @NotNull UUID userId,
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank String failureCode) {
    }

    public record PaymentPostingRequest(
            @NotNull UUID paymentOrderId,
            @NotBlank String paymentOrderNo,
            @NotNull UUID userId,
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank String subject,
            @NotBlank String annualLimitMode) {
    }

    public record RefundPostingRequest(
            @NotNull UUID refundId,
            @NotBlank String refundNo,
            @NotNull UUID userId,
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank String originalPaymentNo) {
    }

    public record MerchantPaymentPostingRequest(
            @NotNull UUID paymentOrderId,
            @NotBlank String paymentOrderNo,
            @NotNull UUID payerUserId,
            @NotNull UUID merchantOwnerUserId,
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank String subject,
            @NotBlank String annualLimitMode,
            @NotBlank String fundingSource,
            @NotBlank String merchantName) {
    }

    public record MerchantRefundPostingRequest(
            @NotNull UUID refundId,
            @NotBlank String refundNo,
            @NotNull UUID payerUserId,
            @NotNull UUID merchantOwnerUserId,
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank String originalPaymentNo,
            @NotBlank String merchantName) {
    }
}
