package com.minipay.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantApplyStore;
import com.minipay.payment.application.port.MerchantApplyStore.ApplyPage;
import com.minipay.payment.application.port.MerchantApplyStore.ApplyView;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.MerchantApply;
import com.minipay.payment.domain.model.MerchantApplyStatus;
import com.minipay.payment.domain.model.MerchantType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入驻审核（ops 侧）：列表/详情/通过/驳回/要求补资料。
 * 审核通过复用 BD 代建的商户创建路径（MerchantManagementService.create），
 * 内部自动解析并绑定 identity 商户拥有者账户。
 */
@Service
public class MerchantApplyService {
    private final MerchantApplyStore applies;
    private final MerchantStore merchants;
    private final MerchantManagementService merchantManagement;
    private final IdempotencyStore idempotency;
    private final OperationAuditStore audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantApplyService(
            MerchantApplyStore applies,
            MerchantStore merchants,
            MerchantManagementService merchantManagement,
            IdempotencyStore idempotency,
            OperationAuditStore audits,
            ObjectMapper objectMapper,
            Clock clock) {
        this.applies = applies;
        this.merchants = merchants;
        this.merchantManagement = merchantManagement;
        this.idempotency = idempotency;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApplyPage list(int page, int size, MerchantApplyStatus applyStatus, UUID userId) {
        if (page < 0 || size < 1 || size > 100) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page must be non-negative and size must be between 1 and 100");
        }
        return applies.findPage(page, size, applyStatus, userId);
    }

    @Transactional(readOnly = true)
    public ApplyView get(long id) {
        return applies.findView(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public ApplyPage listForOwner(UUID userId, int page, int size) {
        ApplyPage result = list(page, size, null, userId);
        return new ApplyPage(
                result.items().stream().map(MerchantApplyService::normalizeConsumerView).toList(),
                result.page(), result.size(), result.total());
    }

    @Transactional
    public ApplyView submit(
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
            String idempotencyKey,
            String requestId) {
        Submission submission = normalizeSubmission(merchantType, shopName, mccCode, address,
                latitude, longitude, shopImages, contactName, contactMobile, contactEmail, remark);
        String operation = "merchant-apply:submit";
        String requestDigest = DigestService.sha256(submission.digestInput());
        IdempotencyStore.Claim claim = claim(userId.toString(), operation, idempotencyKey,
                requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return normalizeConsumerView(replay);
        }
        Instant now = clock.instant();
        if (!applies.reserveOwner(userId, now)) {
            throw new OpsBusinessException(HttpStatus.CONFLICT,
                    "MERCHANT_ONBOARDING_ALREADY_EXISTS",
                    "This user already has a merchant onboarding application");
        }
        MerchantApply apply = new MerchantApply(0L, userId, submission.merchantType(),
                submission.shopName(), submission.mccCode(), submission.address(),
                submission.latitude(), submission.longitude(), submission.shopImages(),
                submission.contactName(), submission.contactMobile(),
                submission.contactEmail(), submission.remark(), MerchantApplyStatus.PENDING,
                null, null, null, now, null, 0L, now, now);
        long id = applies.insert(apply);
        if (!applies.bindApplication(userId, id, now)) {
            throw new IllegalStateException("MERCHANT_ONBOARDING_GUARD_BIND_FAILED");
        }
        ApplyView result = applies.findView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), userId.toString(), "MERCHANT_APPLY_SUBMIT",
                "MERCHANT_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(userId.toString(), operation, idempotencyKey, HttpStatus.CREATED.value(), result);
        return normalizeConsumerView(result);
    }

    @Transactional
    public ApplyView resubmit(
            UUID userId,
            long id,
            long expectedVersion,
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
            String idempotencyKey,
            String requestId) {
        Submission submission = normalizeSubmission(merchantType, shopName, mccCode, address,
                latitude, longitude, shopImages, contactName, contactMobile, contactEmail, remark);
        String operation = "merchant-apply:resubmit:" + id;
        String requestDigest = DigestService.sha256(expectedVersion + "|" + submission.digestInput());
        IdempotencyStore.Claim claim = claim(userId.toString(), operation, idempotencyKey,
                requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return normalizeConsumerView(replay);
        }
        MerchantApply before = applies.find(id).orElseThrow(() -> notFound(id));
        if (!userId.equals(before.userId())) {
            throw notFound(id);
        }
        if (!applies.isBoundApplication(userId, id)) {
            throw notFound(id);
        }
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != MerchantApplyStatus.SUPPLEMENT
                && before.applyStatus() != MerchantApplyStatus.REJECTED) {
            throw new OpsBusinessException(HttpStatus.CONFLICT,
                    "MERCHANT_APPLY_NOT_RESUBMITTABLE",
                    "Only supplemented or rejected applications can be resubmitted");
        }
        if (applies.existsOpenByOwnerAndShop(
                userId, submission.normalizedShopName(), id)) {
            throw duplicateOpen();
        }
        Instant now = clock.instant();
        MerchantApply after = before.resubmit(submission.merchantType(), submission.shopName(),
                submission.mccCode(), submission.address(), submission.latitude(),
                submission.longitude(), submission.shopImages(), submission.contactName(),
                submission.contactMobile(), submission.contactEmail(), submission.remark(), now);
        if (!applies.updateSubmission(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), userId.toString(), "MERCHANT_APPLY_RESUBMIT",
                "MERCHANT_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(userId.toString(), operation, idempotencyKey, HttpStatus.OK.value(), result);
        return normalizeConsumerView(result);
    }

    @Transactional
    public ApplyView approve(
            long id,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "merchant-apply:approve:" + id;
        String requestDigest = DigestService.sha256(Long.toString(expectedVersion));
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        MerchantApply before = applies.find(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != MerchantApplyStatus.PENDING) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_APPLY_NOT_PENDING",
                    "Only a pending application can be approved");
        }

        // 复用 BD 代建的创建路径：按申请里的「联系电话」解析/创建归属账号并生成商户，
        // 保证商户归属手机号与其联系电话一致（手机号是唯一归属键）。
        MerchantStore.MerchantView merchant = merchantManagement.createForApprovedApplicantByMobile(
                before.contactMobile(), before.contactName(), before.shopName(),
                shortName(before.shopName()), before.contactEmail(), before.remark(),
                before.merchantType(), before.mccCode(), before.address(),
                before.latitude(), before.longitude(), before.shopImages(),
                actorId, idempotencyKey, requestId);

        Instant now = clock.instant();
        MerchantApply after = before.approve(merchant.merchantId(), actorId, now);
        if (!applies.updateAudit(after, expectedVersion, now)) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_APPLY_VERSION_CONFLICT",
                    "Application was changed by another operator; reload and retry");
        }
        if (!applies.bindMerchant(before.userId(), id, merchant.merchantId(), now)) {
            throw new IllegalStateException("MERCHANT_ONBOARDING_MERCHANT_BIND_FAILED");
        }
        ApplyView result = applies.findView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), actorId, "MERCHANT_APPLY_APPROVE", "MERCHANT_APPLY",
                applyAuditId(id), null, null, requestId, now);
        // TODO 预留：推送审核通过通知给申请人（MQ 占位，商户自助端上线后接入）
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
        String operation = "merchant-apply:reject:" + id;
        String requestDigest = DigestService.sha256(reason + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        MerchantApply before = applies.find(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != MerchantApplyStatus.PENDING) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_APPLY_NOT_PENDING",
                    "Only a pending application can be rejected");
        }
        Instant now = clock.instant();
        MerchantApply after = before.reject(reason, actorId, now);
        if (!applies.updateAudit(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), actorId, "MERCHANT_APPLY_REJECT", "MERCHANT_APPLY",
                applyAuditId(id), null, null, requestId, now);
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
        String operation = "merchant-apply:supplement:" + id;
        String requestDigest = DigestService.sha256(reason + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplyView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        MerchantApply before = applies.find(id).orElseThrow(() -> notFound(id));
        requireVersion(before, expectedVersion);
        if (before.applyStatus() != MerchantApplyStatus.PENDING) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_APPLY_NOT_PENDING",
                    "Only a pending application can be asked for supplement");
        }
        Instant now = clock.instant();
        MerchantApply after = before.requestSupplement(reason, actorId, now);
        if (!applies.updateAudit(after, expectedVersion, now)) {
            throw versionConflict();
        }
        ApplyView result = applies.findView(id).orElseThrow(() -> notFound(id));
        audits.append(UuidV7.generate(clock), actorId, "MERCHANT_APPLY_SUPPLEMENT",
                "MERCHANT_APPLY", applyAuditId(id), null, null, requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    private static Submission normalizeSubmission(
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
            String remark) {
        String normalizedName = normalizeRequired(shopName, 2, 64, "INVALID_SHOP_NAME");
        String normalizedContactName = normalizeRequired(
                contactName, 2, 64, "INVALID_CONTACT_NAME");
        String normalizedMobile = normalizeRequired(
                contactMobile, 11, 32, "INVALID_CONTACT_MOBILE");
        if (!normalizedMobile.matches("^1[3-9]\\d{9}$")) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_CONTACT_MOBILE", "Contact mobile is invalid");
        }
        String normalizedEmail = normalizeOptional(contactEmail, 254);
        if (normalizedEmail != null && !normalizedEmail.matches(
                "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_CONTACT_EMAIL", "Contact email is invalid");
        }
        BigDecimal normalizedLatitude = normalizeLatitude(latitude);
        BigDecimal normalizedLongitude = normalizeLongitude(longitude);
        if (normalizedLatitude == null || normalizedLongitude == null) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "LOCATION_REQUIRED",
                    "Latitude and longitude are required");
        }
        String normalizedImages = normalizeShopImages(shopImages);
        return new Submission(
                normalizeConsumerMerchantType(merchantType),
                normalizedName,
                normalizedName.toLowerCase(java.util.Locale.ROOT),
                normalizeOptional(mccCode, 16),
                normalizeOptional(address, 200),
                normalizedLatitude,
                normalizedLongitude,
                normalizedImages,
                normalizedContactName,
                normalizedMobile,
                normalizedEmail,
                normalizeOptional(remark, 500));
    }

    static MerchantType normalizeConsumerMerchantType(MerchantType requestedType) {
        return MerchantType.INDIVIDUAL;
    }

    static ApplyView normalizeConsumerView(ApplyView view) {
        return new ApplyView(
                view.id(), view.userId(), MerchantType.INDIVIDUAL.name(), view.shopName(),
                view.mccCode(), view.address(), view.latitude(), view.longitude(),
                view.shopImages(), view.contactName(), view.contactMobile(), view.contactEmail(),
                view.remark(), view.applyStatus(), view.rejectReason(), view.auditAdminId(),
                view.resultantMerchantId(), view.applyTime(), view.auditTime(), view.version(),
                view.createdAt(), view.updatedAt());
    }

    private static String normalizeShopImages(String shopImages) {
        String normalized = normalizeOptional(shopImages, 4000);
        if (normalized == null) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "SHOP_IMAGES_REQUIRED",
                    "At least one shop image is required");
        }
        java.util.List<String> keys = java.util.Arrays.stream(normalized.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        if (keys.size() < 1 || keys.size() > 5 || keys.stream().anyMatch(key -> key.length() > 512)) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_SHOP_IMAGES",
                    "Shop images must contain between one and five object keys");
        }
        return String.join(",", keys);
    }

    private static BigDecimal normalizeLatitude(BigDecimal latitude) {
        if (latitude == null) {
            return null;
        }
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_LATITUDE",
                    "Latitude must be between -90 and 90");
        }
        return latitude;
    }

    private static BigDecimal normalizeLongitude(BigDecimal longitude) {
        if (longitude == null) {
            return null;
        }
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_LONGITUDE",
                    "Longitude must be between -180 and 180");
        }
        return longitude;
    }

    private static String normalizeRequired(
            String value, int min, int max, String code) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, code,
                    "Value length is outside the accepted range");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_FIELD_LENGTH",
                    "Value must not exceed " + max + " characters");
        }
        return normalized;
    }

    private void requireVersion(MerchantApply apply, long expectedVersion) {
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

    private static String shortName(String shopName) {
        return shopName.length() <= 32 ? shopName : shopName.substring(0, 32);
    }

    /**
     * merchant_apply.id 是自增 BIGINT，审计表的 resource_id 是 BINARY(16)（UUID）。
     * 用高 64 位为零的固定 UUID 映射，保证同一申请可检索。
     */
    private static UUID applyAuditId(long id) {
        return new UUID(0L, id);
    }

    private static OpsBusinessException notFound(long id) {
        return new OpsBusinessException(HttpStatus.NOT_FOUND, "MERCHANT_APPLY_NOT_FOUND",
                "Merchant apply " + id + " was not found");
    }

    private static OpsBusinessException versionConflict() {
        return new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_APPLY_VERSION_CONFLICT",
                "Application was changed by another operator; reload and retry");
    }

    private static OpsBusinessException duplicateOpen() {
        return new OpsBusinessException(HttpStatus.CONFLICT,
                "MERCHANT_APPLY_DUPLICATE_OPEN",
                "This account already has an unfinished application for the same shop name");
    }

    private record Submission(
            MerchantType merchantType,
            String shopName,
            String normalizedShopName,
            String mccCode,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String shopImages,
            String contactName,
            String contactMobile,
            String contactEmail,
            String remark) {
        String digestInput() {
            return merchantType + "|" + normalizedShopName + "|" + mccCode + "|" + address
                    + "|" + latitude + "|" + longitude + "|" + shopImages + "|" + contactName
                    + "|" + contactMobile + "|" + contactEmail + "|" + remark;
        }
    }
}
