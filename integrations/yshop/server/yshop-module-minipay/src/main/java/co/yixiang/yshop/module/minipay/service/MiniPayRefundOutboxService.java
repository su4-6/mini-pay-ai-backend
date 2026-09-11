package co.yixiang.yshop.module.minipay.service;

import co.yixiang.yshop.module.order.api.minipay.MiniPayRefundApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiniPayRefundOutboxService implements MiniPayRefundApi {
    private final JdbcTemplate jdbc;

    public MiniPayRefundOutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void requestApprovedFullRefund(String orderNo, BigDecimal amount, String reason) {
        List<Payment> payments = jdbc.query("""
                SELECT order_ref_id, amount_cent, payment_status, refund_status
                  FROM yshop_minipay_payment WHERE yshop_order_no = ? FOR UPDATE
                """, (rs, ignored) -> new Payment(rs.getString("order_ref_id"),
                rs.getLong("amount_cent"), rs.getString("payment_status"),
                rs.getString("refund_status")), orderNo);
        if (payments.isEmpty()) throw new MiniPayProblem("MINIPAY_PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND);
        Payment payment = payments.get(0);
        if (!"PAID".equals(payment.paymentStatus())) {
            throw new MiniPayProblem("MINIPAY_PAYMENT_NOT_REFUNDABLE", HttpStatus.CONFLICT);
        }
        if (MiniPayFoodService.toCent(amount) != payment.amountCent()) {
            throw new MiniPayProblem("MINIPAY_FULL_REFUND_REQUIRED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ("PROCESSING".equals(payment.refundStatus()) || "REFUNDED".equals(payment.refundStatus())) return;
        String requestId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO yshop_minipay_refund_outbox
                  (request_id, yshop_order_no, order_ref_id, reason, status,
                   attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, NOW(6), NOW(6), NOW(6))
                """, requestId, orderNo, payment.orderRefId(), reason);
        jdbc.update("""
                UPDATE yshop_minipay_payment
                   SET refund_status = 'PROCESSING', updated_at = NOW(6)
                 WHERE yshop_order_no = ?
                """, orderNo);
    }

    private record Payment(String orderRefId, long amountCent, String paymentStatus, String refundStatus) { }
}
