package com.minipay.payment.application.port;

import com.minipay.payment.domain.model.ApplicationApply;
import com.minipay.payment.domain.model.ApplicationApplyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationApplyStore {
    ApplyPage findPage(
            int page, int size, UUID userId, ApplicationApplyStatus applyStatus);

    Optional<ApplyView> findApplyView(long id);

    Optional<ApplicationApply> findApply(long id);

    long insert(ApplicationApply apply);

    boolean updateAudit(
            ApplicationApply apply, long expectedVersion, Instant updatedAt);

    boolean updateResubmit(
            ApplicationApply apply, long expectedVersion, Instant updatedAt);

    /** 同商户是否存在进行中的申请（PENDING/SUPPLEMENT，SUPPLEMENT 需重提而非新建）。 */
    boolean existsPendingByMerchant(UUID merchantId);

    /** 同商户进行中的申请中是否存在同名（排除自身，重提改名时用）。 */
    boolean existsByName(UUID merchantId, String name, long excludeApplyId);

    record ApplyPage(List<ApplyView> items, int page, int size, long total) {
    }

    record ApplyView(
            long id,
            UUID userId,
            UUID merchantId,
            String merchantNo,
            String merchantName,
            String name,
            ApplicationApplyStatus applyStatus,
            String rejectReason,
            String auditAdminId,
            UUID resultantApplicationId,
            Instant applyTime,
            Instant auditTime,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }
}
