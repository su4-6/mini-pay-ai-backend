package com.minipay.payment.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MerchantApply(
        long id,
        UUID userId,
        MerchantType merchantType,
        String shopName,
        String mccCode,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
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

    public MerchantApply approve(UUID merchantId, String adminId, Instant now) {
        return new MerchantApply(id, userId, merchantType, shopName, mccCode, address,
                latitude, longitude, shopImages, contactName, contactMobile, contactEmail, remark,
                MerchantApplyStatus.APPROVED, null, adminId, merchantId,
                applyTime, now, version + 1, createdAt, now);
    }

    public MerchantApply reject(String reason, String adminId, Instant now) {
        return new MerchantApply(id, userId, merchantType, shopName, mccCode, address,
                latitude, longitude, shopImages, contactName, contactMobile, contactEmail, remark,
                MerchantApplyStatus.REJECTED, reason, adminId, null,
                applyTime, now, version + 1, createdAt, now);
    }

    public MerchantApply requestSupplement(String reason, String adminId, Instant now) {
        return new MerchantApply(id, userId, merchantType, shopName, mccCode, address,
                latitude, longitude, shopImages, contactName, contactMobile, contactEmail, remark,
                MerchantApplyStatus.SUPPLEMENT, reason, adminId, null,
                applyTime, now, version + 1, createdAt, now);
    }

    public MerchantApply resubmit(
            MerchantType newMerchantType,
            String newShopName,
            String newMccCode,
            String newAddress,
            BigDecimal newLatitude,
            BigDecimal newLongitude,
            String newShopImages,
            String newContactName,
            String newContactMobile,
            String newContactEmail,
            String newRemark,
            Instant now) {
        if (applyStatus != MerchantApplyStatus.SUPPLEMENT
                && applyStatus != MerchantApplyStatus.REJECTED) {
            throw new IllegalStateException("Only supplemented or rejected applications can be resubmitted");
        }
        return new MerchantApply(id, userId, newMerchantType, newShopName, newMccCode,
                newAddress, newLatitude, newLongitude, newShopImages, newContactName, newContactMobile,
                newContactEmail, newRemark, MerchantApplyStatus.PENDING, null, null, null,
                now, null, version + 1, createdAt, now);
    }
}
