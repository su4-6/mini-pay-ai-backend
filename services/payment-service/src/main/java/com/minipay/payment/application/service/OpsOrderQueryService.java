package com.minipay.payment.application.service;

import com.minipay.payment.infrastructure.persistence.OpsMapper;
import com.minipay.payment.infrastructure.persistence.OpsMapper.PaymentOrderRow;
import com.minipay.payment.infrastructure.persistence.OpsMapper.RefundOrderRow;
import com.minipay.payment.infrastructure.persistence.OpsMapper.TransferOrderRow;
import com.minipay.payment.infrastructure.persistence.OpsMapper.RechargeOrderRow;
import com.minipay.payment.infrastructure.persistence.OpsMapper.WithdrawalOrderRow;
import com.minipay.payment.infrastructure.persistence.OpsMapper.AdminBankCardRow;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Ops-portal read-only queries over payment/refund/transfer orders. */
@Service
public class OpsOrderQueryService {

    public record PaymentOrderView(
            String paymentOrderNo, String merchantId, String merchantNo, String merchantName,
            String appId, String merchantOrderNo, long amountCent, String currency,
            String channel, String subject, String status, String payerMasked,
            LocalDateTime createdAt) {}

    public record PaymentOrderDetailView(
            String paymentOrderNo, String merchantId, String merchantNo, String merchantName,
            String appId, String merchantOrderNo, long amountCent, String currency,
            String channel, String subject, String status, String payerMasked,
            LocalDateTime createdAt, String failureCode, LocalDateTime updatedAt) {}

    public record RefundView(
            String refundNo, String paymentOrderNo, String merchantId, String merchantNo,
            String merchantName, long amountCent, String status, LocalDateTime createdAt) {}

    public record RefundDetailView(RefundView view, String reason, LocalDateTime updatedAt) {}

    public record TransferView(
            String transferNo, String payerMasked, String receiverMasked,
            long amountCent, String status, LocalDateTime createdAt) {}

    public record TransferDetailView(
            String transferNo, String payerMasked, String receiverMasked,
            long amountCent, String status, LocalDateTime createdAt,
            String failureCode, LocalDateTime updatedAt) {}

    public record RechargeView(
            String rechargeNo, String userMasked, long amountCent, String channel,
            String bankName, String bankCardMasked, String status, LocalDateTime createdAt) {}

    public record RechargeDetailView(
            String rechargeNo, String userMasked, long amountCent, String channel,
            String bankName, String bankCardMasked, String status, LocalDateTime createdAt,
            String failureCode, LocalDateTime updatedAt) {}

    public record WithdrawalView(
            String withdrawalNo, String userMasked, long amountCent, String bankName,
            String bankCardMasked, String status, LocalDateTime createdAt) {}

    public record WithdrawalDetailView(
            String withdrawalNo, String userMasked, long amountCent, String bankName,
            String bankCardMasked, String status, LocalDateTime createdAt,
            String bankRequestNo, String failureCode, LocalDateTime updatedAt) {}

    public record AdminBankCardView(
            String cardId, String ownerUserId, String provider, String bankName, String cardType,
            String maskedCardNo, String holderName, String status, LocalDateTime verifiedAt,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record PaymentOrderPage(List<PaymentOrderView> items, int page, int size, long total) {}
    public record RefundPage(List<RefundView> items, int page, int size, long total) {}
    public record TransferPage(List<TransferView> items, int page, int size, long total) {}
    public record RechargePage(List<RechargeView> items, int page, int size, long total) {}
    public record WithdrawalPage(List<WithdrawalView> items, int page, int size, long total) {}

    private final OpsMapper mapper;

    public OpsOrderQueryService(OpsMapper mapper) {
        this.mapper = mapper;
    }

    public PaymentOrderPage payments(
            int page, int size, UUID merchantId, String merchantNo, String name, String appId,
            String status, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from == null ? null : from.atStartOfDay();
        LocalDateTime toDt = to == null ? null : to.plusDays(1).atStartOfDay();
        byte[] merchantIdBytes = merchantId == null ? null : uuidBytes(merchantId);
        List<PaymentOrderRow> rows = mapper.findPayments(
                merchantIdBytes, merchantNo, name, appId, status, fromDt, toDt, size, page * size);
        long total = mapper.countPayments(
                merchantIdBytes, merchantNo, name, appId, status, fromDt, toDt);
        return new PaymentOrderPage(rows.stream().map(this::toPaymentView).toList(), page, size, total);
    }

    public PaymentOrderDetailView payment(String paymentOrderNo) {
        PaymentOrderRow row = mapper.findPaymentByNo(paymentOrderNo);
        if (row == null) {
            throw new OpsBusinessException(
                    HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "支付订单不存在");
        }
        PaymentOrderView view = toPaymentView(row);
        return new PaymentOrderDetailView(
                view.paymentOrderNo(), view.merchantId(), view.merchantNo(), view.merchantName(),
                view.appId(), view.merchantOrderNo(), view.amountCent(), view.currency(),
                view.channel(), view.subject(), view.status(), view.payerMasked(), view.createdAt(),
                row.failureCode, row.updatedAt);
    }

    public RefundPage refunds(
            int page, int size, UUID merchantId, String merchantNo, String name,
            String status, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from == null ? null : from.atStartOfDay();
        LocalDateTime toDt = to == null ? null : to.plusDays(1).atStartOfDay();
        byte[] merchantIdBytes = merchantId == null ? null : uuidBytes(merchantId);
        List<RefundOrderRow> rows = mapper.findRefunds(
                merchantIdBytes, merchantNo, name, status, fromDt, toDt, size, page * size);
        long total = mapper.countRefunds(
                merchantIdBytes, merchantNo, name, status, fromDt, toDt);
        return new RefundPage(rows.stream().map(this::toRefundView).toList(), page, size, total);
    }

    public RefundDetailView refund(String refundOrderNo) {
        RefundOrderRow row = mapper.findRefundByNo(refundOrderNo);
        if (row == null) {
            throw new OpsBusinessException(
                    HttpStatus.NOT_FOUND, "REFUND_ORDER_NOT_FOUND", "退款订单不存在");
        }
        return new RefundDetailView(toRefundView(row), row.reason, row.updatedAt);
    }

    public TransferPage transfers(
            int page, int size, String transferNo, String status, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from == null ? null : from.atStartOfDay();
        LocalDateTime toDt = to == null ? null : to.plusDays(1).atStartOfDay();
        List<TransferOrderRow> rows = mapper.findTransfers(
                transferNo, status, fromDt, toDt, size, page * size);
        long total = mapper.countTransfers(transferNo, status, fromDt, toDt);
        return new TransferPage(rows.stream().map(this::toTransferView).toList(), page, size, total);
    }

    public TransferDetailView transfer(String transferNo) {
        TransferOrderRow row = mapper.findTransferByNo(transferNo);
        if (row == null) {
            throw new OpsBusinessException(
                    HttpStatus.NOT_FOUND, "TRANSFER_ORDER_NOT_FOUND", "转账订单不存在");
        }
        TransferView view = toTransferView(row);
        return new TransferDetailView(
                view.transferNo(), view.payerMasked(), view.receiverMasked(), view.amountCent(),
                view.status(), view.createdAt(), row.failureCode, row.updatedAt);
    }

    public RechargePage recharges(
            int page, int size, String rechargeNo, String status, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from == null ? null : from.atStartOfDay();
        LocalDateTime toDt = to == null ? null : to.plusDays(1).atStartOfDay();
        List<RechargeOrderRow> rows = mapper.findRecharges(
                rechargeNo, status, fromDt, toDt, size, page * size);
        return new RechargePage(rows.stream().map(this::toRechargeView).toList(), page, size,
                mapper.countRecharges(rechargeNo, status, fromDt, toDt));
    }

    public RechargeDetailView recharge(String rechargeNo) {
        RechargeOrderRow row = mapper.findRechargeByNo(rechargeNo);
        if (row == null) throw new OpsBusinessException(
                HttpStatus.NOT_FOUND, "RECHARGE_ORDER_NOT_FOUND", "充值订单不存在");
        RechargeView view = toRechargeView(row);
        return new RechargeDetailView(
                view.rechargeNo(), view.userMasked(), view.amountCent(), view.channel(),
                view.bankName(), view.bankCardMasked(), view.status(), view.createdAt(),
                row.failureCode, row.updatedAt);
    }

    public WithdrawalPage withdrawals(
            int page, int size, String withdrawalNo, String status, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from == null ? null : from.atStartOfDay();
        LocalDateTime toDt = to == null ? null : to.plusDays(1).atStartOfDay();
        List<WithdrawalOrderRow> rows = mapper.findWithdrawals(
                withdrawalNo, status, fromDt, toDt, size, page * size);
        return new WithdrawalPage(rows.stream().map(this::toWithdrawalView).toList(), page, size,
                mapper.countWithdrawals(withdrawalNo, status, fromDt, toDt));
    }

    public WithdrawalDetailView withdrawal(String withdrawalNo) {
        WithdrawalOrderRow row = mapper.findWithdrawalByNo(withdrawalNo);
        if (row == null) throw new OpsBusinessException(
                HttpStatus.NOT_FOUND, "WITHDRAWAL_ORDER_NOT_FOUND", "提现订单不存在");
        WithdrawalView view = toWithdrawalView(row);
        return new WithdrawalDetailView(
                view.withdrawalNo(), view.userMasked(), view.amountCent(), view.bankName(),
                view.bankCardMasked(), view.status(), view.createdAt(), row.bankRequestNo,
                row.failureCode, row.updatedAt);
    }

    public List<AdminBankCardView> bankCards(UUID userId) {
        return mapper.findAdminBankCards(uuidBytes(userId)).stream().map(row ->
                new AdminBankCardView(uuidText(row.cardId), uuidText(row.userId), row.provider,
                        row.bankName, row.cardType, row.maskedCardNo, row.holderName, row.status,
                        row.verifiedAt, row.createdAt, row.updatedAt)).toList();
    }

    private PaymentOrderView toPaymentView(PaymentOrderRow row) {
        return new PaymentOrderView(
                row.payOrderNo, uuidText(row.merchantId), row.merchantNo, row.merchantName,
                row.appId, row.merchantOrderNo, row.amountCent, row.currency, row.channel,
                row.subject, row.status, maskUuid(row.payerUserId), row.createdAt);
    }

    private RefundView toRefundView(RefundOrderRow row) {
        return new RefundView(
                row.refundOrderNo, row.paymentOrderNo, uuidText(row.merchantId),
                row.merchantNo, row.merchantName, row.amountCent, row.status, row.createdAt);
    }

    private TransferView toTransferView(TransferOrderRow row) {
        return new TransferView(
                row.transferNo, maskUuid(row.payerUserId), maskUuid(row.receiverUserId),
                row.amountCent, row.status, row.createdAt);
    }

    private RechargeView toRechargeView(RechargeOrderRow row) {
        return new RechargeView(row.rechargeNo, maskUuid(row.userId), row.amountCent, row.channel,
                row.bankName, row.maskedCardNo, row.status, row.createdAt);
    }

    private WithdrawalView toWithdrawalView(WithdrawalOrderRow row) {
        return new WithdrawalView(row.withdrawalNo, maskUuid(row.userId), row.amountCent,
                row.bankName, row.maskedCardNo, row.status, row.createdAt);
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static String uuidText(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static String maskUuid(byte[] bytes) {
        String text = uuidText(bytes);
        if (text == null) {
            return null;
        }
        return text.substring(0, 8) + "****";
    }
}
