package com.minipay.payment.application.port;

import com.minipay.payment.domain.model.ApplicationStatus;
import com.minipay.payment.domain.model.MerchantApplication;
import com.minipay.payment.domain.model.MerchantStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationStore {
    ApplicationPage findPage(
            int page, int size, String appId, String name, UUID merchantId,
            ApplicationStatus status, Boolean unavailable);

    ApplicationSummary getSummary();

    Optional<ApplicationView> findApplicationView(UUID applicationId);

    Optional<MerchantApplication> findApplication(UUID applicationId);

    boolean nameExists(UUID merchantId, String name, UUID excludingApplicationId);

    void insert(MerchantApplication application);

    boolean updateName(MerchantApplication application, long expectedVersion);

    boolean updateStatus(
            UUID applicationId, ApplicationStatus status, long expectedVersion, Instant updatedAt);

    boolean deleteApplication(UUID applicationId, long expectedVersion);

    Dependencies dependencies(UUID applicationId, String appId);

    record ApplicationPage(List<ApplicationView> items, int page, int size, long total) {
    }

    record ApplicationView(
            UUID applicationId,
            String appId,
            String name,
            UUID merchantId,
            String merchantNo,
            String merchantName,
            MerchantStatus merchantStatus,
            ApplicationStatus status,
            boolean hasTransactions,
            boolean deletable,
            String deletionBlockedReason,
            long recentTransactionCount,
            Instant lastTransactionAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    record ApplicationSummary(
            long totalCount,
            long activeCount,
            long disabledCount,
            long unavailableCount) {
    }

    record Dependencies(long transactionCount, long referenceCount) {
        public boolean hasTransactions() {
            return transactionCount > 0;
        }

        public boolean hasReferences() {
            return referenceCount > 0;
        }
    }
}
