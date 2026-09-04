package com.minipay.payment.application.port;

import com.minipay.payment.domain.model.Merchant;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.domain.model.MerchantType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantStore {
    MerchantPage findPage(
            int page, int size, String merchantNo, String name, String contactMobile,
            MerchantStatus status);

    Optional<MerchantView> findView(UUID merchantId);

    Optional<Merchant> find(UUID merchantId);

    void insert(Merchant merchant);

    void updateSource(UUID merchantId, String source);

    boolean updateProfile(Merchant merchant, long expectedVersion);

    boolean updateStatus(
            UUID merchantId, MerchantStatus status, long expectedVersion, Instant updatedAt);

    boolean updateFreeze(
            UUID merchantId, MerchantStatus status, String freezeReason,
            long expectedVersion, Instant updatedAt);

    boolean delete(UUID merchantId, long expectedVersion);

    Dependencies dependencies(UUID merchantId);

    record MerchantPage(List<MerchantView> items, int page, int size, long total) {
    }

    record MerchantView(
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
            boolean profileComplete,
            MerchantStatus status,
            String freezeReason,
            boolean accountLinked,
            UUID ownerUserId,
            long applicationCount,
            boolean deletable,
            String deletionBlockedReason,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    record Dependencies(long applicationCount, long transactionCount) {
        public boolean exists() {
            return applicationCount > 0 || transactionCount > 0;
        }
    }
}
