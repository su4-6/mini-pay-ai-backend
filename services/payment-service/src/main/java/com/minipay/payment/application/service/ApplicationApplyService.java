package com.minipay.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.port.ApplicationApplyStore;
import com.minipay.payment.application.port.ApplicationApplyStore.ApplyPage;
import com.minipay.payment.application.port.ApplicationApplyStore.ApplyView;
import com.minipay.payment.application.port.ApplicationStore;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.ApplicationApply;
import com.minipay.payment.domain.model.ApplicationApplyStatus;
import com.minipay.payment.domain.model.MerchantStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用申请审核（ops 侧）：列表/详情/通过/驳回/要求补资料/重提。
 * 审核通过复用 BD 代建的应用创建路径（ApplicationManagementService.create）。
 * 提交/重提（merchant-web，未来）走同一服务。
 */
@Service
public class ApplicationApplyService {
    private final ApplicationApplyStore applies;
    private final MerchantStore merchants;
    private final ApplicationStore applications;
    private final ApplicationManagementService applicationManagement;
    private final MerchantService merchantPlatform;
    private final IdempotencyStore idempotency;
    private final OperationAuditStore audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApplicationApplyService(
            ApplicationApplyStore applies,
            MerchantStore merchants,
            ApplicationStore applications,
            ApplicationManagementService applicationManagement,
            MerchantService merchantPlatform,
            IdempotencyStore idempotency,
            OperationAuditStore audits,
            ObjectMapper objectMapper,
            Clock clock) {
        this.applies = applies;
        this.merchants = merchants;
        this.applications = applications;
        this.applicationManagement = applicationManagement;
        this.merchantPlatform = merchantPlatform;
        this.idempotency = idempotency;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApplyPage list(int page, int size, UUID userId, ApplicationApplyStatus applyStatus) {
        if (page < 0 || size < 1 || size > 100) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page must be non-negative and size must be between 1 and 100");
        }
        return applies.findPage(page, size, userId, applyStatus);
    }

    @Transactional(readOnly = true)
    public ApplyView get(long id) {
        return applies.findApplyView(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public ApplyView submit(
            UUID merchantId,
            String name,
            UUID userId,
            String idempotencyKey,
            String requestId) {
        String normalizedName = normalizeName(name);
        String operation = "application-apply:submit";
        String requestDigest = DigestService.sha256(merchantId + "|" + normalizedName);
        IdempotencyStore.Claim claim = claim(userId.toString(), operation, idempotencyKey,
                requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        requireActiveMerchant(merchantId);
        if (applies.existsPendingByMerchant(merchantId)) {
            throw duplicatePending();
        }
        ensureNameAvailable(merchantId, normalizedName, 0L);

        Instant now = clock.instant();
        ApplicationApply apply = new ApplicationApply(
                0L, userId, merchantId, normalizedName, ApplicationApplyStatus.PENDING,
                null, null, null, now, null, 0L, now, now);
        long id = applies.insert(apply);
        ApplyView result = applies.findApplyView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), userId.toString(), "APPLICATION_APPLY_SUBMIT",
                "APPLICATION_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(userId.toString(), operation, idempotencyKey, HttpStatus.CREATED.value(), result);
        return result;
    }

    @Transactional
    public ApplyView approve(
            long id,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "application-apply:approve:" + id;
        String requestDigest = DigestService.sha256(Long.toString(expectedVersion));
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        ApplicationApply before = applies.findApply(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != ApplicationApplyStatus.PENDING) {
            throw notPending();
        }
        requireActiveMerchant(before.merchantId());
        ensureNameAvailable(before.merchantId(), before.name(), before.id());

        // 复用 BD 代建的创建路径：内部生成 appId + 幂等 + 审计。
        ApplicationStore.ApplicationView application = applicationManagement.create(
                before.merchantId(), before.name(),
                com.minipay.payment.domain.model.ApplicationStatus.DISABLED, actorId,
                idempotencyKey, requestId);
        merchantPlatform.provisionApprovedApplication(
                before.merchantId(), application.applicationId(), application.appId(), actorId);

        Instant now = clock.instant();
        ApplicationApply after = before.approve(application.applicationId(), actorId, now);
        if (!applies.updateAudit(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findApplyView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), actorId, "APPLICATION_APPLY_APPROVE",
                "APPLICATION_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public ApplyView reject(
            long id,
            String reason,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "application-apply:reject:" + id;
        String requestDigest = DigestService.sha256(reason + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        ApplicationApply before = applies.findApply(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != ApplicationApplyStatus.PENDING) {
            throw notPending();
        }
        Instant now = clock.instant();
        ApplicationApply after = before.reject(reason, actorId, now);
        if (!applies.updateAudit(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findApplyView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), actorId, "APPLICATION_APPLY_REJECT",
                "APPLICATION_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public ApplyView requestSupplement(
            long id,
            String reason,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "application-apply:supplement:" + id;
        String requestDigest = DigestService.sha256(reason + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        ApplicationApply before = applies.findApply(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != ApplicationApplyStatus.PENDING) {
            throw notPending();
        }
        Instant now = clock.instant();
        ApplicationApply after = before.requestSupplement(reason, actorId, now);
        if (!applies.updateAudit(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findApplyView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), actorId, "APPLICATION_APPLY_SUPPLEMENT",
                "APPLICATION_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public ApplyView resubmit(
            long id,
            String name,
            long expectedVersion,
            UUID userId,
            String idempotencyKey,
            String requestId) {
        String normalizedName = normalizeName(name);
        String operation = "application-apply:resubmit:" + id;
        String requestDigest = DigestService.sha256(normalizedName + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(userId.toString(), operation, idempotencyKey,
                requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        ApplicationApply before = applies.findApply(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != ApplicationApplyStatus.REJECTED
                && before.applyStatus() != ApplicationApplyStatus.SUPPLEMENT) {
            throw new OpsBusinessException(HttpStatus.CONFLICT,
                    "APPLICATION_APPLY_NOT_RESUBMITTABLE",
                    "Only a rejected or supplement-requested application can be resubmitted");
        }
        requireActiveMerchant(before.merchantId());
        ensureNameAvailable(before.merchantId(), normalizedName, before.id());

        Instant now = clock.instant();
        ApplicationApply after = before.resubmit(normalizedName, now);
        if (!applies.updateResubmit(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findApplyView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), userId.toString(), "APPLICATION_APPLY_RESUBMIT",
                "APPLICATION_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(userId.toString(), operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    private void requireActiveMerchant(UUID merchantId) {
        var merchant = merchants.find(merchantId)
                .orElseThrow(() -> merchantNotFound(merchantId));
        if (merchant.status() != MerchantStatus.ACTIVE) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_NOT_ACTIVE",
                    "The merchant must be active (current: " + merchant.status() + ")");
        }
    }

    private void ensureNameAvailable(UUID merchantId, String name, long excludeApplyId) {
        // 已存在的正式应用（含审核通过生成的应用）。
        if (applications.nameExists(merchantId, name, null)) {
            throw nameConflict();
        }
        // 同商户进行中的其他申请（submit 无自身可排除，excludeApplyId 传 0）。
        if (applies.existsByName(merchantId, name, excludeApplyId)) {
            throw nameConflict();
        }
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.length() < 2 || normalized.length() > 64) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_APPLICATION_NAME",
                    "Application name must contain between 2 and 64 characters");
        }
        return normalized;
    }

    private void requireVersion(ApplicationApply apply, long expectedVersion) {
        if (apply.version() != expectedVersion) {
            throw versionConflict();
        }
    }

    private IdempotencyStore.Claim claim(
            String actorId, String operation, String key, String requestDigest) {
        IdempotencyStore.Claim claim = idempotency.claim(
                UuidV7.generate(clock), actorId, operation, key, requestDigest, clock.instant());
        if (!requestDigest.equals(claim.requestDigest())) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used with a different request");
        }
        return claim;
    }

    private ApplyView replay(IdempotencyStore.Claim claim) {
        if (claim.responseJson().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(claim.responseJson().orElseThrow(), ApplyView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    private void complete(
            String actorId, String operation, String key, int status, ApplyView response) {
        try {
            idempotency.complete(actorId, operation, key, status,
                    objectMapper.writeValueAsString(response), clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize idempotency response", exception);
        }
    }

    /**
     * application_apply.id 是自增 BIGINT，审计表的 resource_id 是 BINARY(16)（UUID）。
     * 用高 64 位为零的固定 UUID 映射，保证同一申请可检索。
     */
    private static UUID applyAuditId(long id) {
        return new UUID(0L, id);
    }

    private static OpsBusinessException notFound(long id) {
        return new OpsBusinessException(HttpStatus.NOT_FOUND, "APPLICATION_APPLY_NOT_FOUND",
                "Application apply " + id + " was not found");
    }

    private static OpsBusinessException merchantNotFound(UUID merchantId) {
        return new OpsBusinessException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND",
                "Merchant " + merchantId + " was not found");
    }

    private static OpsBusinessException notPending() {
        return new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_APPLY_NOT_PENDING",
                "Only a pending application can be acted on");
    }

    private static OpsBusinessException duplicatePending() {
        return new OpsBusinessException(HttpStatus.CONFLICT,
                "APPLICATION_APPLY_DUPLICATE_PENDING",
                "The merchant already has an application in progress");
    }

    private static OpsBusinessException nameConflict() {
        return new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_APPLY_NAME_CONFLICT",
                "An application with this name already exists for the merchant");
    }

    private static OpsBusinessException versionConflict() {
        return new OpsBusinessException(HttpStatus.CONFLICT,
                "APPLICATION_APPLY_VERSION_CONFLICT",
                "Application was changed by another operator; reload and retry");
    }
}
