package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record MerchantApplication(
        UUID applicationId,
        String appId,
        UUID merchantId,
        String name,
        ApplicationStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public MerchantApplication rename(String newName, Instant now) {
        return new MerchantApplication(applicationId, appId, merchantId, newName, status,
                version + 1, createdAt, now);
    }

    public MerchantApplication changeStatus(ApplicationStatus target, Instant now) {
        return status == target ? this : new MerchantApplication(
                applicationId, appId, merchantId, name, target, version + 1, createdAt, now);
    }
}
