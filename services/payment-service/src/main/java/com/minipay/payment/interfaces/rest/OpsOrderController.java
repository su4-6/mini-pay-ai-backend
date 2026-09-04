package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.OpsOrderQueryService;
import com.minipay.payment.application.service.OpsOrderQueryService.PaymentOrderDetailView;
import com.minipay.payment.application.service.OpsOrderQueryService.PaymentOrderPage;
import com.minipay.payment.application.service.OpsOrderQueryService.RefundDetailView;
import com.minipay.payment.application.service.OpsOrderQueryService.RefundPage;
import com.minipay.payment.application.service.OpsOrderQueryService.TransferDetailView;
import com.minipay.payment.application.service.OpsOrderQueryService.TransferPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ops-portal read-only order queries: payments / refunds / transfers / recharges / withdrawals. */
@Validated
@RestController
@RequestMapping("/api/v1/ops")
public class OpsOrderController {

    private final OpsOrderQueryService queries;

    public OpsOrderController(OpsOrderQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/payments")
    public PaymentOrderPage payments(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.payments(page, size, merchantId, merchantNo, name, appId, status, from, to);
    }

    @GetMapping("/payments/{paymentOrderNo}")
    public PaymentOrderDetailView payment(@PathVariable String paymentOrderNo) {
        return queries.payment(paymentOrderNo);
    }

    @GetMapping("/refunds")
    public RefundPage refunds(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.refunds(page, size, merchantId, merchantNo, name, status, from, to);
    }

    @GetMapping("/refunds/{refundOrderNo}")
    public RefundDetailView refund(@PathVariable String refundOrderNo) {
        return queries.refund(refundOrderNo);
    }

    @GetMapping("/transfers")
    public TransferPage transfers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String transferNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.transfers(page, size, transferNo, status, from, to);
    }

    @GetMapping("/transfers/{transferOrderNo}")
    public TransferDetailView transfer(@PathVariable String transferOrderNo) {
        return queries.transfer(transferOrderNo);
    }

    @GetMapping("/recharges")
    public OpsOrderQueryService.RechargePage recharges(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String rechargeNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.recharges(page, size, rechargeNo, status, from, to);
    }

    @GetMapping("/recharges/{rechargeNo}")
    public OpsOrderQueryService.RechargeDetailView recharge(@PathVariable String rechargeNo) {
        return queries.recharge(rechargeNo);
    }

    @GetMapping("/withdrawals")
    public OpsOrderQueryService.WithdrawalPage withdrawals(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String withdrawalNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.withdrawals(page, size, withdrawalNo, status, from, to);
    }

    @GetMapping("/withdrawals/{withdrawalNo}")
    public OpsOrderQueryService.WithdrawalDetailView withdrawal(@PathVariable String withdrawalNo) {
        return queries.withdrawal(withdrawalNo);
    }
}
