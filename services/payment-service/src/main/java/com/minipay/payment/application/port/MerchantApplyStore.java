package com.minipay.payment.application.port;

import com.minipay.payment.domain.model.MerchantApply;
import com.minipay.payment.domain.model.MerchantApplyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantApplyStore {
    ApplyPage findPage(
            int page, int size, MerchantApplyStatus applyStatus, UUID userId);

    Optional<ApplyView> findView(long id);

    Optional<MerchantApply> find(long id);

    long insert(MerchantApply apply);

    boolean reserveOwner(UUID userId, Instant now);

    boolean bindApplication(UUID userId, long applyId, Instant now);

    boolean isBoundApplication(UUID userId, long applyId);

    boolean bindMerchant(UUID userId, long applyId, UUID merchantId, Instant now);

    boolean existsOpenByOwnerAndShop(UUID userId, String normalizedShopName, long excludingId);

    boolean updateSubmission(MerchantApply apply, long expectedVersion, Instant updatedAt);

    boolean updateAudit(
            MerchantApply apply, long expectedVersion, Instant updatedAt);

    record ApplyPage(List<ApplyView> items, int page, int size, long total) {
    }

    record ApplyView(
            long id,
            UUID userId,
            String merchantType,
            String shopName,
            String mccCode,
            String address,
            java.math.BigDecimal latitude,
            java.math.BigDecimal longitude,
            String shopImages,
            String contactName,
            String contactMobile,
            String contactEmail,
            String remark,
            MerchantApplyStatus applyStatus,
            String rejectReason,
            String auditAdminId,
            UUID resultantMerchantId,
            Instant applyTime,
            Instant auditTime,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }
}
