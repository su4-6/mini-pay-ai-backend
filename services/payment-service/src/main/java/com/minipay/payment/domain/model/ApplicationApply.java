package com.minipay.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 商户创建应用申请。无 DRAFT 态：记录创建时即为 PENDING。
 * 审核通过时回写 resultantApplicationId（指向已创建的 merchant_application）。
 */
public record ApplicationApply(
        long id,
        UUID userId,
        UUID merchantId,
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

    public ApplicationApply approve(UUID applicationId, String adminId, Instant now) {
        return new ApplicationApply(id, userId, merchantId, name,
                ApplicationApplyStatus.APPROVED, null, adminId, applicationId,
                applyTime, now, version + 1, createdAt, now);
    }

    public ApplicationApply reject(String reason, String adminId, Instant now) {
        return new ApplicationApply(id, userId, merchantId, name,
                ApplicationApplyStatus.REJECTED, reason, adminId, null,
                applyTime, now, version + 1, createdAt, now);
    }

    public ApplicationApply requestSupplement(String reason, String adminId, Instant now) {
        return new ApplicationApply(id, userId, merchantId, name,
                ApplicationApplyStatus.SUPPLEMENT, reason, adminId, null,
                applyTime, now, version + 1, createdAt, now);
    }

    public ApplicationApply resubmit(String newName, Instant now) {
        return new ApplicationApply(id, userId, merchantId, newName,
                ApplicationApplyStatus.PENDING, null, null, null,
                now, null, version + 1, createdAt, now);
    }
}
