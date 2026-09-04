package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.service.MerchantApiPaymentService;
import com.minipay.payment.application.service.MerchantApiPaymentService.BillPage;
import com.minipay.payment.application.service.MerchantApiPaymentService.CreateOrderRequest;
import com.minipay.payment.application.service.MerchantApiPaymentService.OrderView;
import com.minipay.payment.domain.model.Refund;
import com.minipay.payment.infrastructure.security.MerchantApiContext;
import com.minipay.payment.infrastructure.security.MerchantApiSignatureFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Server-to-server merchant-application payment API (authenticated by HMAC signature). */
@Validated
@RestController
@RequestMapping("/api/v1/merchant-api")
public class MerchantApiController {

    private final MerchantApiPaymentService payments;

    public MerchantApiController(MerchantApiPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(
            @RequestAttribute(MerchantApiSignatureFilter.CONTEXT_ATTRIBUTE) MerchantApiContext context,
            @Valid @RequestBody CreateOrderBody body) {
        return payments.createOrder(context, new CreateOrderRequest(
                body.merchantOrderNo(), body.amountCent(), body.subject(),
                body.channel(), body.payerUserId()));
    }

    @GetMapping("/orders/{paymentOrderNo}")
    public OrderView getOrder(
            @RequestAttribute(MerchantApiSignatureFilter.CONTEXT_ATTRIBUTE) MerchantApiContext context,
            @PathVariable String paymentOrderNo) {
        return payments.getOrder(context, paymentOrderNo);
    }

    @PostMapping("/orders/{paymentOrderNo}/refunds")
    public Refund refund(
            @RequestAttribute(MerchantApiSignatureFilter.CONTEXT_ATTRIBUTE) MerchantApiContext context,
            @PathVariable String paymentOrderNo,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody RefundBody body) {
        return payments.refund(context, paymentOrderNo, idempotencyKey, body.amountCent(), body.reason());
    }

    @GetMapping("/bills")
    public BillPage bills(
            @RequestAttribute(MerchantApiSignatureFilter.CONTEXT_ATTRIBUTE) MerchantApiContext context,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return payments.bills(context, page, size, from, to);
    }

    public record CreateOrderBody(
            @NotBlank @Size(max = 128) String merchantOrderNo,
            @Min(1) @Max(1_000_000) long amountCent,
            @NotBlank @Size(max = 256) String subject,
            @Size(max = 32) String channel,
            @NotNull UUID payerUserId) {
    }

    public record RefundBody(
            @Min(1) @Max(1_000_000) long amountCent,
            @Size(max = 256) String reason) {
    }
}
