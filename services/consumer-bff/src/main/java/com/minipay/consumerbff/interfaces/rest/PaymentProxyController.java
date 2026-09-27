package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.ConsumerSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * 扫商户收款码付款（沙箱）：与转账同构的三步，绝不合并。
 *
 * <ol>
 *   <li>{@code POST /api/v1/payments/scan} 解析收款码（个人码与商户码返回的结构不同，原样透传）；
 *   <li>{@code POST /api/v1/payments/prepare} 创建商户支付单，并记住本会话准备的金额；
 *   <li>{@code POST /api/v1/payments/{paymentOrderId}/confirm} 支付密码换一次性授权令牌，再确认支付。
 * </ol>
 *
 * <p>支付密码只出现在第 3 步的请求体里，直接交给 Identity，不落库、不落日志、不回显；确认金额必须与
 * prepare 时准备的金额一致，否则在换取授权令牌之前就被拒。
 */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentProxyController {

    private final ConsumerSessionService sessions;

    public PaymentProxyController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/scan")
    public Mono<ResponseEntity<String>> scan(
            WebSession session,
            ServerWebExchange exchange,
            @Valid @RequestBody ScanRequest request) {
        return sessions.resolveCollectionCode(
                        session,
                        exchange,
                        request.deepLink(),
                        request.merchantToken(),
                        RequestIds.of(exchange))
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/prepare")
    public Mono<ResponseEntity<Map<String, Object>>> prepare(
            WebSession session,
            ServerWebExchange exchange,
            @Valid @RequestBody PreparePaymentRequest request) {
        return sessions.prepareMerchantPayment(
                        session,
                        exchange,
                        request.resolutionId(),
                        request.amountFen(),
                        RequestIds.of(exchange))
                .map(preparation -> UpstreamResponses.preparedPayment(
                        preparation.paymentOrderId(),
                        preparation.paymentOrderNo(),
                        preparation.amountFen(),
                        preparation.expiresAt()));
    }

    @PostMapping("/{paymentOrderId}/confirm")
    public Mono<ResponseEntity<Map<String, Object>>> confirm(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable String paymentOrderId,
            @Valid @RequestBody ConfirmPaymentRequest request) {
        return sessions.confirmMerchantPayment(
                        session,
                        exchange,
                        paymentOrderId,
                        request.amountFen(),
                        request.paymentPassword(),
                        RequestIds.of(exchange))
                .map(confirmation -> UpstreamResponses.confirmedPayment(
                        confirmation.paymentOrderNo(),
                        confirmation.status(),
                        confirmation.failureCode()));
    }

    /** Either the scanned deep link or a bare merchant token; upstream requires at least one. */
    public record ScanRequest(
            @Size(max = 2048) String deepLink, @Size(max = 512) String merchantToken) {
    }

    public record PreparePaymentRequest(
            @NotBlank @Size(max = 64) String resolutionId,
            @Min(1) @Max(1_000_000) long amountFen) {
    }

    /**
     * {@code amountFen} is required and positive: it bounds the one-time authorization, so a missing
     * field must fail loudly instead of defaulting to zero.
     */
    public record ConfirmPaymentRequest(
            @Min(1) @Max(1_000_000) long amountFen,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String paymentPassword) {
    }
}
