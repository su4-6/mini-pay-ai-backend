package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.RechargeService;
import com.minipay.payment.application.service.WithdrawalService;
import com.minipay.payment.domain.model.RechargeOrder;
import com.minipay.payment.domain.model.WithdrawalOrder;
import com.minipay.payment.domain.model.FundingOrderPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FundingOrderController {
    private final RechargeService recharges;
    private final WithdrawalService withdrawals;

    public FundingOrderController(
            RechargeService recharges,
            WithdrawalService withdrawals) {
        this.recharges = recharges;
        this.withdrawals = withdrawals;
    }

    @PostMapping("/recharge-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public RechargeOrder recharge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FundingRequest request) {
        return recharges.create(
                ConsumerClaims.requireReadyUser(jwt, true),
                idempotencyKey,
                request.bankCardId(),
                request.amountCent());
    }

    @GetMapping("/recharge-orders/{rechargeId}")
    public RechargeOrder getRecharge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID rechargeId) {
        return recharges.get(
                ConsumerClaims.requireReadyUser(jwt, false), rechargeId);
    }

    @GetMapping("/recharge-orders")
    public FundingOrderPage listRecharges(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return recharges.list(ConsumerClaims.requireReadyUser(jwt, false), page, size);
    }

    @PostMapping("/recharge-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public RechargeOrder prepareRecharge(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FundingRequest request) {
        return recharges.prepare(ConsumerClaims.requireReadyUser(jwt, true), idempotencyKey,
                request.bankCardId(), request.amountCent());
    }

    @PostMapping("/recharge-intents/{rechargeId}/confirm")
    public RechargeOrder confirmRecharge(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID rechargeId,
            @Valid @RequestBody ConfirmFundingRequest request) {
        return recharges.confirm(ConsumerClaims.requireReadyUser(jwt, false), rechargeId,
                request.paymentAuthToken(), ConsumerClaims.requireDeviceId(jwt));
    }

    @PostMapping("/withdrawal-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalOrder withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FundingRequest request) {
        return withdrawals.create(
                ConsumerClaims.requireReadyUser(jwt, true),
                idempotencyKey,
                request.bankCardId(),
                request.amountCent());
    }

    @GetMapping("/withdrawal-orders/{withdrawalId}")
    public WithdrawalOrder getWithdrawal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID withdrawalId) {
        return withdrawals.get(
                ConsumerClaims.requireReadyUser(jwt, false), withdrawalId);
    }

    @GetMapping("/withdrawal-orders")
    public FundingOrderPage listWithdrawals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return withdrawals.list(ConsumerClaims.requireReadyUser(jwt, false), page, size);
    }

    @PostMapping("/withdrawal-orders/{withdrawalId}/confirm")
    public WithdrawalOrder confirmWithdrawal(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID withdrawalId,
            @Valid @RequestBody ConfirmFundingRequest request) {
        return withdrawals.confirm(
                ConsumerClaims.requireReadyUser(jwt, false),
                withdrawalId,
                request.paymentAuthToken(),
                ConsumerClaims.requireDeviceId(jwt));
    }

    public record FundingRequest(
            @NotNull UUID bankCardId,
            @Min(1) @Max(1_000_000) long amountCent) {
    }

    public record ConfirmFundingRequest(@NotBlank String paymentAuthToken) {
    }
}
