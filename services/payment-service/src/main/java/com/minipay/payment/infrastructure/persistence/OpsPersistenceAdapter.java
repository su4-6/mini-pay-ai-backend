package com.minipay.payment.infrastructure.persistence;

import com.minipay.payment.application.port.ApplicationApplyStore;
import com.minipay.payment.application.port.ApplicationStore;
import com.minipay.payment.application.port.DashboardQueryPort;
import com.minipay.payment.application.port.DashboardProjectionPort;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantApplyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.ApplicationApply;
import com.minipay.payment.domain.model.ApplicationApplyStatus;
import com.minipay.payment.domain.model.ApplicationStatus;
import com.minipay.payment.domain.model.Merchant;
import com.minipay.payment.domain.model.MerchantApply;
import com.minipay.payment.domain.model.MerchantApplyStatus;
import com.minipay.payment.domain.model.MerchantApplication;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.domain.model.MerchantType;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class OpsPersistenceAdapter
        implements MerchantStore, MerchantApplyStore, ApplicationStore, ApplicationApplyStore,
        IdempotencyStore, OperationAuditStore, DashboardQueryPort, DashboardProjectionPort {
    private final OpsMapper mapper;

    public OpsPersistenceAdapter(OpsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MerchantPage findPage(
            int page, int size, String merchantNo, String name, String contactMobile,
            MerchantStatus status) {
        String statusValue = status == null ? null : status.name();
        List<MerchantView> items = mapper.findMerchants(
                        merchantNo, name, contactMobile, statusValue, size, page * size)
                .stream().map(OpsPersistenceAdapter::toView).toList();
        return new MerchantPage(
                items, page, size,
                mapper.countMerchants(merchantNo, name, contactMobile, statusValue));
    }

    @Override
    public Optional<MerchantView> findView(UUID merchantId) {
        return Optional.ofNullable(mapper.findMerchant(bytes(merchantId)))
                .map(OpsPersistenceAdapter::toView);
    }

    @Override
    public Optional<Merchant> find(UUID merchantId) {
        return Optional.ofNullable(mapper.findMerchant(bytes(merchantId)))
                .map(OpsPersistenceAdapter::toMerchant);
    }

    @Override
    public void insert(Merchant merchant) {
        OpsMapper.MerchantRow row = new OpsMapper.MerchantRow();
        row.merchantId = bytes(merchant.merchantId());
        row.merchantNo = merchant.merchantNo();
        row.name = merchant.name();
        copyProfile(merchant, row);
        row.status = merchant.status().name();
        row.ownerUserId = merchant.ownerUserId() == null ? null : bytes(merchant.ownerUserId());
        row.version = merchant.version();
        row.createdAt = utc(merchant.createdAt());
        row.updatedAt = utc(merchant.updatedAt());
        if (mapper.insertMerchant(row) != 1) {
            throw new IllegalStateException("Merchant insert did not affect one row");
        }
    }

    @Override
    public boolean updateProfile(Merchant merchant, long expectedVersion) {
        OpsMapper.MerchantRow row = new OpsMapper.MerchantRow();
        row.merchantId = bytes(merchant.merchantId());
        row.name = merchant.name();
        copyProfile(merchant, row);
        row.expectedVersion = expectedVersion;
        row.updatedAt = utc(merchant.updatedAt());
        return mapper.updateMerchantProfile(row) == 1;
    }

    @Override
    public boolean updateStatus(
            UUID merchantId, MerchantStatus status, long expectedVersion, Instant updatedAt) {
        return mapper.updateMerchantStatus(
                bytes(merchantId), status.name(), expectedVersion, utc(updatedAt)) == 1;
    }

    @Override
    public boolean updateFreeze(
            UUID merchantId, MerchantStatus status, String freezeReason,
            long expectedVersion, Instant updatedAt) {
        return mapper.updateMerchantFreeze(
                bytes(merchantId), status.name(), freezeReason, expectedVersion,
                utc(updatedAt)) == 1;
    }

    @Override
    public boolean delete(UUID merchantId, long expectedVersion) {
        return mapper.deleteMerchant(bytes(merchantId), expectedVersion) == 1;
    }

    @Override
    public MerchantStore.Dependencies dependencies(UUID merchantId) {
        OpsMapper.DependencyRow row = mapper.dependencies(bytes(merchantId));
        return new MerchantStore.Dependencies(row.applicationCount, row.transactionCount);
    }

    @Override
    public MerchantApplyStore.ApplyPage findPage(
            int page, int size, MerchantApplyStatus applyStatus, UUID userId) {
        String statusValue = applyStatus == null ? null : applyStatus.name();
        byte[] userBytes = userId == null ? null : bytes(userId);
        List<MerchantApplyStore.ApplyView> items = mapper.findApplies(
                        statusValue, userBytes, size, page * size)
                .stream().map(OpsPersistenceAdapter::toApplyView).toList();
        return new MerchantApplyStore.ApplyPage(items, page, size,
                mapper.countApplies(statusValue, userBytes));
    }

    @Override
    public Optional<MerchantApplyStore.ApplyView> findView(long id) {
        return Optional.ofNullable(mapper.findApply(id))
                .map(OpsPersistenceAdapter::toApplyView);
    }

    @Override
    public Optional<MerchantApply> find(long id) {
        return Optional.ofNullable(mapper.findApply(id))
                .map(OpsPersistenceAdapter::toApply);
    }

    @Override
    public long insert(MerchantApply apply) {
        OpsMapper.ApplyRow row = applyRow(apply);
        if (mapper.insertApply(row) != 1) {
            throw new IllegalStateException("Merchant apply insert did not affect one row");
        }
        return row.id;
    }

    @Override
    public boolean reserveOwner(UUID userId, Instant now) {
        return mapper.reserveOnboardingOwner(bytes(userId), utc(now)) == 1;
    }

    @Override
    public boolean bindApplication(UUID userId, long applyId, Instant now) {
        return mapper.bindOnboardingApplication(bytes(userId), applyId, utc(now)) == 1;
    }

    @Override
    public boolean isBoundApplication(UUID userId, long applyId) {
        return mapper.isBoundOnboardingApplication(bytes(userId), applyId);
    }

    @Override
    public boolean bindMerchant(UUID userId, long applyId, UUID merchantId, Instant now) {
        return mapper.bindOnboardingMerchant(bytes(userId), applyId, bytes(merchantId), utc(now)) == 1;
    }

    @Override
    public void updateSource(UUID merchantId, String source) {
        if (mapper.updateMerchantSource(bytes(merchantId), source) != 1) {
            throw new IllegalStateException("MERCHANT_SOURCE_UPDATE_FAILED");
        }
    }

    @Override
    public boolean existsOpenByOwnerAndShop(
            UUID userId, String normalizedShopName, long excludingId) {
        return mapper.existsOpenApplyByOwnerAndShop(
                bytes(userId), normalizedShopName, excludingId);
    }

    @Override
    public boolean updateSubmission(
            MerchantApply apply, long expectedVersion, Instant updatedAt) {
        OpsMapper.ApplyRow row = applyRow(apply);
        row.expectedVersion = expectedVersion;
        row.updatedAt = utc(updatedAt);
        return mapper.updateApplySubmission(row) == 1;
    }

    @Override
    public boolean updateAudit(MerchantApply apply, long expectedVersion, Instant updatedAt) {
        OpsMapper.ApplyRow row = applyRow(apply);
        row.expectedVersion = expectedVersion;
        row.updatedAt = utc(updatedAt);
        return mapper.updateApplyAudit(row) == 1;
    }

    private static MerchantApplyStore.ApplyView toApplyView(OpsMapper.ApplyRow row) {
        return new MerchantApplyStore.ApplyView(
                row.id, uuid(row.userId), row.merchantType, row.shopName, row.mccCode,
                row.address, row.latitude, row.longitude, row.shopImages,
                row.contactName, row.contactMobile,
                row.contactEmail, row.remark, MerchantApplyStatus.valueOf(row.applyStatus),
                row.rejectReason, row.auditAdminId,
                row.resultantMerchantId == null ? null : uuid(row.resultantMerchantId),
                instant(row.applyTime),
                row.auditTime == null ? null : instant(row.auditTime),
                row.version, instant(row.createdAt), instant(row.updatedAt));
    }

    private static MerchantApply toApply(OpsMapper.ApplyRow row) {
        return new MerchantApply(
                row.id, uuid(row.userId), MerchantType.valueOf(row.merchantType),
                row.shopName, row.mccCode, row.address, row.latitude, row.longitude,
                row.shopImages,
                row.contactName, row.contactMobile, row.contactEmail, row.remark,
                MerchantApplyStatus.valueOf(row.applyStatus), row.rejectReason,
                row.auditAdminId,
                row.resultantMerchantId == null ? null : uuid(row.resultantMerchantId),
                instant(row.applyTime),
                row.auditTime == null ? null : instant(row.auditTime),
                row.version, instant(row.createdAt), instant(row.updatedAt));
    }

    private static OpsMapper.ApplyRow applyRow(MerchantApply apply) {
        OpsMapper.ApplyRow row = new OpsMapper.ApplyRow();
        row.id = apply.id();
        row.userId = apply.userId() == null ? null : bytes(apply.userId());
        row.merchantType = apply.merchantType().name();
        row.shopName = apply.shopName();
        row.mccCode = apply.mccCode();
        row.address = apply.address();
        row.latitude = apply.latitude();
        row.longitude = apply.longitude();
        row.shopImages = apply.shopImages();
        row.contactName = apply.contactName();
        row.contactMobile = apply.contactMobile();
        row.contactEmail = apply.contactEmail();
        row.remark = apply.remark();
        row.applyStatus = apply.applyStatus().name();
        row.rejectReason = apply.rejectReason();
        row.auditAdminId = apply.auditAdminId();
        row.resultantMerchantId = apply.resultantMerchantId() == null
                ? null : bytes(apply.resultantMerchantId());
        row.applyTime = utc(apply.applyTime());
        row.auditTime = apply.auditTime() == null ? null : utc(apply.auditTime());
        row.version = apply.version();
        row.createdAt = utc(apply.createdAt());
        row.updatedAt = utc(apply.updatedAt());
        return row;
    }

    @Override
    public ApplicationApplyStore.ApplyPage findPage(
            int page, int size, UUID userId, ApplicationApplyStatus applyStatus) {
        String statusValue = applyStatus == null ? null : applyStatus.name();
        byte[] userBytes = userId == null ? null : bytes(userId);
        List<ApplicationApplyStore.ApplyView> items = mapper.findApplicationApplies(
                        userBytes, statusValue, size, page * size)
                .stream().map(OpsPersistenceAdapter::toApplicationApplyView).toList();
        return new ApplicationApplyStore.ApplyPage(items, page, size,
                mapper.countApplicationApplies(userBytes, statusValue));
    }

    @Override
    public Optional<ApplicationApplyStore.ApplyView> findApplyView(long id) {
        return Optional.ofNullable(mapper.findApplicationApply(id))
                .map(OpsPersistenceAdapter::toApplicationApplyView);
    }

    @Override
    public Optional<ApplicationApply> findApply(long id) {
        return Optional.ofNullable(mapper.findApplicationApply(id))
                .map(OpsPersistenceAdapter::toApplicationApply);
    }

    @Override
    public long insert(ApplicationApply apply) {
        OpsMapper.ApplicationApplyRow row = applicationApplyRow(apply);
        if (mapper.insertApplicationApply(row) != 1) {
            throw new IllegalStateException("Application apply insert did not affect one row");
        }
        return row.id;
    }

    @Override
    public boolean updateAudit(
            ApplicationApply apply, long expectedVersion, Instant updatedAt) {
        OpsMapper.ApplicationApplyRow row = applicationApplyRow(apply);
        row.expectedVersion = expectedVersion;
        row.updatedAt = utc(updatedAt);
        return mapper.updateApplicationApplyAudit(row) == 1;
    }

    @Override
    public boolean updateResubmit(
            ApplicationApply apply, long expectedVersion, Instant updatedAt) {
        OpsMapper.ApplicationApplyRow row = applicationApplyRow(apply);
        row.expectedVersion = expectedVersion;
        row.updatedAt = utc(updatedAt);
        return mapper.updateApplicationApplyResubmit(row) == 1;
    }

    @Override
    public boolean existsPendingByMerchant(UUID merchantId) {
        return mapper.existsPendingByMerchant(bytes(merchantId));
    }

    @Override
    public boolean existsByName(UUID merchantId, String name, long excludeApplyId) {
        return mapper.existsByName(bytes(merchantId), name, excludeApplyId);
    }

    private static ApplicationApplyStore.ApplyView toApplicationApplyView(
            OpsMapper.ApplicationApplyRow row) {
        return new ApplicationApplyStore.ApplyView(
                row.id, uuid(row.userId), uuid(row.merchantId), row.merchantNo,
                row.merchantName, row.name, ApplicationApplyStatus.valueOf(row.applyStatus),
                row.rejectReason, row.auditAdminId,
                row.resultantApplicationId == null ? null : uuid(row.resultantApplicationId),
                instant(row.applyTime),
                row.auditTime == null ? null : instant(row.auditTime),
                row.version, instant(row.createdAt), instant(row.updatedAt));
    }

    private static ApplicationApply toApplicationApply(OpsMapper.ApplicationApplyRow row) {
        return new ApplicationApply(
                row.id, uuid(row.userId), uuid(row.merchantId), row.name,
                ApplicationApplyStatus.valueOf(row.applyStatus), row.rejectReason,
                row.auditAdminId,
                row.resultantApplicationId == null ? null : uuid(row.resultantApplicationId),
                instant(row.applyTime),
                row.auditTime == null ? null : instant(row.auditTime),
                row.version, instant(row.createdAt), instant(row.updatedAt));
    }

    private static OpsMapper.ApplicationApplyRow applicationApplyRow(ApplicationApply apply) {
        OpsMapper.ApplicationApplyRow row = new OpsMapper.ApplicationApplyRow();
        row.id = apply.id();
        row.userId = apply.userId() == null ? null : bytes(apply.userId());
        row.merchantId = apply.merchantId() == null ? null : bytes(apply.merchantId());
        row.name = apply.name();
        row.applyStatus = apply.applyStatus().name();
        row.rejectReason = apply.rejectReason();
        row.auditAdminId = apply.auditAdminId();
        row.resultantApplicationId = apply.resultantApplicationId() == null
                ? null : bytes(apply.resultantApplicationId());
        row.applyTime = utc(apply.applyTime());
        row.auditTime = apply.auditTime() == null ? null : utc(apply.auditTime());
        row.version = apply.version();
        row.createdAt = utc(apply.createdAt());
        row.updatedAt = utc(apply.updatedAt());
        return row;
    }

    @Override
    public ApplicationPage findPage(
            int page, int size, String appId, String name, UUID merchantId,
            ApplicationStatus status, Boolean unavailable) {
        byte[] merchantBytes = merchantId == null ? null : bytes(merchantId);
        String statusValue = status == null ? null : status.name();
        boolean unavailableFilter = Boolean.TRUE.equals(unavailable);
        List<ApplicationView> items = mapper.findApplications(
                        appId, name, merchantBytes, statusValue, unavailableFilter, size, page * size)
                .stream().map(OpsPersistenceAdapter::toApplicationView).toList();
        return new ApplicationPage(items, page, size,
                mapper.countApplications(appId, name, merchantBytes, statusValue, unavailableFilter));
    }

    @Override
    public ApplicationStore.ApplicationSummary getSummary() {
        OpsMapper.ApplicationSummaryRow row = mapper.applicationSummary();
        return new ApplicationStore.ApplicationSummary(
                row.totalCount, row.activeCount, row.disabledCount, row.unavailableCount);
    }

    @Override
    public Optional<ApplicationView> findApplicationView(UUID applicationId) {
        return Optional.ofNullable(mapper.findApplication(bytes(applicationId)))
                .map(OpsPersistenceAdapter::toApplicationView);
    }

    @Override
    public Optional<MerchantApplication> findApplication(UUID applicationId) {
        return Optional.ofNullable(mapper.findApplication(bytes(applicationId)))
                .map(OpsPersistenceAdapter::toApplication);
    }

    @Override
    public boolean nameExists(UUID merchantId, String name, UUID excludingApplicationId) {
        return mapper.countApplicationName(bytes(merchantId), name,
                excludingApplicationId == null ? null : bytes(excludingApplicationId)) > 0;
    }

    @Override
    public void insert(MerchantApplication application) {
        OpsMapper.ApplicationRow row = applicationRow(application);
        if (mapper.insertApplication(row) != 1) {
            throw new IllegalStateException("Application insert did not affect one row");
        }
    }

    @Override
    public boolean updateName(MerchantApplication application, long expectedVersion) {
        OpsMapper.ApplicationRow row = applicationRow(application);
        row.expectedVersion = expectedVersion;
        return mapper.updateApplicationName(row) == 1;
    }

    @Override
    public boolean updateStatus(
            UUID applicationId, ApplicationStatus status, long expectedVersion, Instant updatedAt) {
        return mapper.updateApplicationStatus(bytes(applicationId), status.name(), expectedVersion,
                utc(updatedAt)) == 1;
    }

    @Override
    public boolean deleteApplication(UUID applicationId, long expectedVersion) {
        return mapper.deleteApplication(bytes(applicationId), expectedVersion) == 1;
    }

    @Override
    public ApplicationStore.Dependencies dependencies(UUID applicationId, String appId) {
        OpsMapper.ApplicationDependencyRow row = mapper.applicationDependencies(
                bytes(applicationId), appId);
        return new ApplicationStore.Dependencies(row.transactionCount, row.referenceCount);
    }

    @Override
    public Claim claim(
            UUID recordId,
            String actorId,
            String operation,
            String key,
            String requestDigest,
            Instant createdAt) {
        OpsMapper.IdempotencyRow insert = new OpsMapper.IdempotencyRow();
        insert.recordId = bytes(recordId);
        insert.actorId = actorId;
        insert.operation = operation;
        insert.idempotencyKey = key;
        insert.requestDigest = requestDigest;
        insert.createdAt = utc(createdAt);
        boolean created = mapper.insertIdempotency(insert) == 1;
        OpsMapper.IdempotencyRow locked = mapper.lockIdempotency(actorId, operation, key);
        if (locked == null) {
            throw new IllegalStateException("Idempotency record could not be locked");
        }
        return new Claim(created, locked.requestDigest, locked.responseStatus,
                Optional.ofNullable(locked.responseJson));
    }

    @Override
    public void complete(
            String actorId,
            String operation,
            String key,
            int responseStatus,
            String responseJson,
            Instant completedAt) {
        if (mapper.completeIdempotency(
                actorId, operation, key, responseStatus, responseJson, utc(completedAt)) != 1) {
            throw new IllegalStateException("Idempotency completion did not affect one row");
        }
    }

    @Override
    public void append(
            UUID auditId,
            String actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String beforeDigest,
            String afterDigest,
            String requestId,
            Instant occurredAt) {
        OpsMapper.AuditRow row = new OpsMapper.AuditRow();
        row.auditId = bytes(auditId);
        row.actorId = actorId;
        row.action = action;
        row.resourceType = resourceType;
        row.resourceId = bytes(resourceId);
        row.beforeDigest = beforeDigest;
        row.afterDigest = afterDigest;
        row.requestId = requestId;
        row.occurredAt = utc(occurredAt);
        if (mapper.insertAudit(row) != 1) {
            throw new IllegalStateException("Audit insert did not affect one row");
        }
    }

    @Override
    public DashboardAggregate aggregate(LocalDate from, LocalDate to) {
        OpsMapper.MetricAggregateRow row = mapper.aggregate(from, to);
        return new DashboardAggregate(
                row.submittedPaymentCount,
                row.successfulPaymentCount,
                row.paymentAmountCent,
                row.successfulRefundCount,
                row.refundAmountCent,
                row.dataAsOf == null ? null : row.dataAsOf.toInstant(ZoneOffset.UTC));
    }

    @Override
    public List<DailyMetric> trend(LocalDate from, LocalDate to) {
        return mapper.trend(from, to).stream()
                .map(row -> new DailyMetric(
                        row.metricDate,
                        row.submittedPaymentCount,
                        row.successfulPaymentCount,
                        row.paymentAmountCent,
                        row.successfulRefundCount,
                        row.refundAmountCent))
                .toList();
    }

    @Override
    public long activeMerchantCount(LocalDate from, LocalDate to) {
        return mapper.activeMerchantCount(from, to);
    }

    @Override
    public PendingCounts pending(Instant threshold) {
        OpsMapper.PendingRow row = mapper.pending(utc(threshold));
        return new PendingCounts(
                row.abnormalPaymentCount,
                row.abnormalRefundCount,
                row.abnormalTransferCount,
                row.failedNotificationCount);
    }

    @Override
    public boolean markEvent(UUID eventId, String eventType, Instant projectedAt) {
        return mapper.markMetricEvent(bytes(eventId), eventType, utc(projectedAt)) == 1;
    }

    @Override
    public void addPlatformPayment(
            LocalDate date, long submittedCount, long successfulCount, long amountCent, Instant at) {
        requireOne(mapper.addPlatformPayment(
                date, submittedCount, successfulCount, amountCent, utc(at)), "platform payment metric");
    }

    @Override
    public void addMerchantPayment(
            LocalDate date, UUID merchantId, long successfulCount, long amountCent, Instant at) {
        requireOne(mapper.addMerchantPayment(
                date, bytes(merchantId), successfulCount, amountCent, utc(at)), "merchant payment metric");
    }

    @Override
    public void addPlatformRefund(
            LocalDate date, long successfulCount, long amountCent, Instant at) {
        requireOne(mapper.addPlatformRefund(
                date, successfulCount, amountCent, utc(at)), "platform refund metric");
    }

    @Override
    public void addMerchantRefund(
            LocalDate date, UUID merchantId, long successfulCount, long amountCent, Instant at) {
        requireOne(mapper.addMerchantRefund(
                date, bytes(merchantId), successfulCount, amountCent, utc(at)), "merchant refund metric");
    }

    private static MerchantView toView(OpsMapper.MerchantRow row) {
        String reason = row.applicationCount > 0
                ? "MERCHANT_HAS_APPLICATIONS"
                : row.transactionCount > 0 ? "MERCHANT_HAS_TRANSACTIONS" : null;
        UUID ownerUserId = row.ownerUserId == null ? null : uuid(row.ownerUserId);
        return new MerchantView(
                uuid(row.merchantId), row.merchantNo, row.name, row.shortName, row.contactName,
                row.contactMobile, row.contactEmail, row.remark,
                row.merchantType == null ? MerchantType.PERSONAL
                        : MerchantType.valueOf(row.merchantType),
                row.mccCode, row.address, row.latitude, row.longitude, row.shopImages,
                hasText(row.shortName) && hasText(row.contactName) && hasText(row.contactMobile),
                MerchantStatus.valueOf(row.status), row.freezeReason,
                ownerUserId != null, ownerUserId,
                row.applicationCount, reason == null, reason,
                instant(row.createdAt), instant(row.updatedAt), row.version);
    }

    private static Merchant toMerchant(OpsMapper.MerchantRow row) {
        return new Merchant(
                uuid(row.merchantId), row.merchantNo, row.name, row.shortName, row.contactName,
                row.contactMobile, row.contactEmail, row.remark,
                row.merchantType == null ? MerchantType.PERSONAL
                        : MerchantType.valueOf(row.merchantType),
                row.mccCode, row.address, row.latitude, row.longitude, row.shopImages,
                MerchantStatus.valueOf(row.status), row.freezeReason,
                row.ownerUserId == null ? null : uuid(row.ownerUserId), row.version,
                instant(row.createdAt), instant(row.updatedAt));
    }

    private static ApplicationView toApplicationView(OpsMapper.ApplicationRow row) {
        ApplicationStatus status = ApplicationStatus.valueOf(row.status);
        String reason = status != ApplicationStatus.DISABLED
                ? "APPLICATION_MUST_BE_DISABLED"
                : row.transactionCount > 0
                        ? "APPLICATION_HAS_TRANSACTIONS"
                        : row.referenceCount > 0 ? "APPLICATION_HAS_DEPENDENCIES" : null;
        return new ApplicationView(
                uuid(row.applicationId), row.appId, row.name, uuid(row.merchantId),
                row.merchantNo, row.merchantName, MerchantStatus.valueOf(row.merchantStatus),
                status, row.transactionCount > 0, reason == null, reason,
                row.recentTransactionCount,
                row.lastTransactionAt == null ? null : instant(row.lastTransactionAt),
                instant(row.createdAt), instant(row.updatedAt), row.version);
    }

    private static MerchantApplication toApplication(OpsMapper.ApplicationRow row) {
        return new MerchantApplication(
                uuid(row.applicationId), row.appId, uuid(row.merchantId), row.name,
                ApplicationStatus.valueOf(row.status), row.version,
                instant(row.createdAt), instant(row.updatedAt));
    }

    private static OpsMapper.ApplicationRow applicationRow(MerchantApplication application) {
        OpsMapper.ApplicationRow row = new OpsMapper.ApplicationRow();
        row.applicationId = bytes(application.applicationId());
        row.appId = application.appId();
        row.merchantId = bytes(application.merchantId());
        row.name = application.name();
        row.status = application.status().name();
        row.version = application.version();
        row.createdAt = utc(application.createdAt());
        row.updatedAt = utc(application.updatedAt());
        return row;
    }

    private static void copyProfile(Merchant merchant, OpsMapper.MerchantRow row) {
        row.shortName = merchant.shortName();
        row.contactName = merchant.contactName();
        row.contactMobile = merchant.contactMobile();
        row.contactEmail = merchant.contactEmail();
        row.remark = merchant.remark();
        row.merchantType = merchant.merchantType().name();
        row.mccCode = merchant.mccCode();
        row.address = merchant.address();
        row.latitude = merchant.latitude();
        row.longitude = merchant.longitude();
        row.shopImages = merchant.shopImages();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1 && affected != 2) {
            throw new IllegalStateException(operation + " did not update the expected row");
        }
    }
}
