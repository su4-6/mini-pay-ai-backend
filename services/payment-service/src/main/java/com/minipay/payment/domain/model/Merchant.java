package com.minipay.payment.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Merchant(
        UUID merchantId,
        String merchantNo,
        String name,
        String shortName,
        String contactName,
        String contactMobile,
        String contactEmail,
        String remark,
        MerchantType merchantType,
        String mccCode,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String shopImages,
        MerchantStatus status,
        String freezeReason,
        UUID ownerUserId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Merchant updateProfile(
            String newName,
            String newShortName,
            String newContactName,
            String newContactMobile,
            String newContactEmail,
            String newRemark,
            MerchantType newMerchantType,
            String newMccCode,
            String newAddress,
            BigDecimal newLatitude,
            BigDecimal newLongitude,
            String newShopImages,
            Instant now) {
        return new Merchant(merchantId, merchantNo, newName, newShortName, newContactName,
                newContactMobile, newContactEmail, newRemark, newMerchantType, newMccCode,
                newAddress, newLatitude, newLongitude, newShopImages, status, freezeReason,
                ownerUserId, version + 1, createdAt, now);
    }

    public Merchant changeStatus(MerchantStatus target, Instant now) {
        return target == status
                ? this
                : new Merchant(merchantId, merchantNo, name, shortName, contactName,
                        contactMobile, contactEmail, remark, merchantType, mccCode,
                        address, latitude, longitude, shopImages, target, freezeReason,
                        ownerUserId, version + 1, createdAt, now);
    }

    public Merchant freeze(String reason, Instant now) {
        return new Merchant(merchantId, merchantNo, name, shortName, contactName,
                contactMobile, contactEmail, remark, merchantType, mccCode,
                address, latitude, longitude, shopImages, MerchantStatus.FROZEN, reason,
                ownerUserId, version + 1, createdAt, now);
    }

    public Merchant unfreeze(Instant now) {
        return new Merchant(merchantId, merchantNo, name, shortName, contactName,
                contactMobile, contactEmail, remark, merchantType, mccCode,
                address, latitude, longitude, shopImages, MerchantStatus.ACTIVE, null,
                ownerUserId, version + 1, createdAt, now);
    }
}
