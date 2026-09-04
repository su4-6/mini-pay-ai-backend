package com.minipay.payment.application.service;

import com.minipay.payment.domain.model.Refund;
import com.minipay.payment.infrastructure.persistence.PaymentRepository;
import com.minipay.payment.infrastructure.persistence.PaymentRepository.PaymentOrderRow;
import com.minipay.payment.infrastructure.security.MerchantApiContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-to-server merchant-application payment API (aggregate-payment, Alipay-style). */
@Service
public class MerchantApiPaymentService {

    public record CreateOrderRequest(
            String merchantOrderNo, long amountCent, String subject,
            String channel, UUID payerUserId) {
    }

    public record OrderView(
            UUID paymentOrderId, String paymentOrderNo, String merchantOrderNo, long amountCent,
            String currency, String subject, String channel, String status,
            String paymentUrl, Instant expiresAt, Instant createdAt) {
    }

    public record RefundRequest(long amountCent, String reason) {
    }

    public record BillRow(
            String paymentOrderNo, String merchantOrderNo, long amountCent,
            String currency, String channel, String subject, String status,
            LocalDateTime createdAt) {
    }

    public record BillPage(List<BillRow> items, int page, int size, long total) {
    }

    private final PaymentOrderService paymentOrders;
    private final RefundService refunds;
    private final PaymentRepository repository;
    private final JdbcTemplate jdbc;

    public MerchantApiPaymentService(
            PaymentOrderService paymentOrders, RefundService refunds,
            PaymentRepository repository, JdbcTemplate jdbc) {
        this.paymentOrders = paymentOrders;
        this.refunds = refunds;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional
    public OrderView createOrder(MerchantApiContext context, CreateOrderRequest request) {
        String channel = request.channel() == null || request.channel().isBlank()
                ? "WALLET" : request.channel();
        if (!contains(context.availableChannels(), channel)) {
            throw new PaymentProblemException(
                    "PAYMENT_CHANNEL_NOT_ENABLED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String paymentMethod = switch (channel) {
            case "WALLET" -> "WALLET_BALANCE";
            case "ALIPAY" -> "ALIPAY";
            case "WECHAT" -> "WECHAT_PAY";
            default -> throw new PaymentProblemException(
                    "UNSUPPORTED_PAYMENT_CHANNEL", HttpStatus.UNPROCESSABLE_ENTITY);
        };
        paymentOrders.createMerchantOrder(
                context.merchantId(), context.applicationId(), context.appId(),
                request.payerUserId(), request.merchantOrderNo(),
                request.amountCent(), request.subject(), paymentMethod);
        PaymentOrderRow row = repository.findPaymentByMerchantOrder(
                context.appId(), request.merchantOrderNo()).orElseThrow();
        return toView(row);
    }

    public OrderView getOrder(MerchantApiContext context, String paymentOrderNo) {
        PaymentOrderRow row = repository.findPaymentByNo(context.appId(), paymentOrderNo)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        return toView(row);
    }

    @Transactional
    public Refund refund(
            MerchantApiContext context, String paymentOrderNo,
            String idempotencyKey, long amountCent, String reason) {
        PaymentOrderRow order = repository.findPaymentByNo(context.appId(), paymentOrderNo)
                .orElseThrow(() -> new PaymentProblemException(
                        "PAYMENT_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"SUCCEEDED".equals(order.status())) {
            throw new PaymentProblemException(
                    "PAYMENT_NOT_REFUNDABLE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return refunds.create(idempotencyKey, order.paymentOrderId(), amountCent, reason);
    }

    public BillPage bills(MerchantApiContext context, int page, int size, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from == null ? null : from.atStartOfDay();
        LocalDateTime toDt = to == null ? null : to.plusDays(1).atStartOfDay();
        List<BillRow> items = jdbc.query("""
                SELECT pay_order_no, merchant_order_no, amount_cent, currency,
                       channel, subject, status, created_at
                  FROM payment_order
                 WHERE app_id = ?
                   AND (? IS NULL OR created_at >= ?)
                   AND (? IS NULL OR created_at < ?)
                 ORDER BY created_at DESC, pay_order_id DESC
                 LIMIT ? OFFSET ?
                """,
                (rs, ignored) -> new BillRow(
                        rs.getString("pay_order_no"), rs.getString("merchant_order_no"),
                        rs.getLong("amount_cent"), rs.getString("currency"),
                        rs.getString("channel"), rs.getString("subject"), rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                context.appId(), fromDt, fromDt, toDt, toDt, size, page * size);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(1) FROM payment_order
                 WHERE app_id = ?
                   AND (? IS NULL OR created_at >= ?)
                   AND (? IS NULL OR created_at < ?)
                """, Long.class, context.appId(), fromDt, fromDt, toDt, toDt);
        return new BillPage(items, page, size, total == null ? 0 : total);
    }

    private static OrderView toView(PaymentOrderRow row) {
        return new OrderView(
                row.paymentOrderId(), row.paymentOrderNo(),
                row.merchantOrderNo() == null ? "" : row.merchantOrderNo(),
                row.amountCent(), row.currency(), row.subject(), row.paymentMethod(),
                row.status(), row.redirectUrl(), row.expiresAt(), row.updatedAt());
    }

    private static boolean contains(String commaSeparated, String token) {
        return commaSeparated != null
                && Arrays.stream(commaSeparated.split(","))
                .map(String::trim).anyMatch(token::equals);
    }
}
