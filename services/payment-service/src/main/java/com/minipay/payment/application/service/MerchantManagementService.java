package com.minipay.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.port.IdempotencyStore;
import java.math.BigDecimal;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.MerchantStore.MerchantPage;
import com.minipay.payment.application.port.MerchantStore.MerchantView;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.Merchant;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.domain.model.MerchantType;
import com.minipay.payment.infrastructure.client.IdentityServiceClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantManagementService {
    private static final DateTimeFormatter MERCHANT_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private final MerchantStore merchants;
    private final IdempotencyStore idempotency;
    private final OperationAuditStore audits;
    private final IdentityServiceClient identity;
    private final MerchantWalletProvisioner walletProvisioner;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantManagementService(
            MerchantStore merchants,
            IdempotencyStore idempotency,
            OperationAuditStore audits,
            IdentityServiceClient identity,
            MerchantWalletProvisioner walletProvisioner,
            ObjectMapper objectMapper,
            Clock clock) {
        this.merchants = merchants;
        this.idempotency = idempotency;
        this.audits = audits;
        this.identity = identity;
        this.walletProvisioner = walletProvisioner;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MerchantPage list(
            int page, int size, String merchantNo, String name, String contactMobile,
            MerchantStatus status) {
        if (page < 0 || size < 1 || size > 100) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page must be non-negative and size must be between 1 and 100");
        }
        String normalizedMerchantNo = normalizeOptional(merchantNo, 32, "INVALID_MERCHANT_NO");
        String normalizedName = name == null || name.isBlank() ? null : name.trim();
        String normalizedMobile = normalizeOptional(contactMobile, 11, "INVALID_CONTACT_MOBILE");
        return merchants.findPage(page, size, normalizedMerchantNo, normalizedName,
                normalizedMobile, status);
    }

    @Transactional(readOnly = true)
    public MerchantView get(UUID merchantId) {
        return merchants.findView(merchantId).orElseThrow(() -> notFound(merchantId));
    }

    @Transactional
    public MerchantView create(
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
            String actorId,
            String idempotencyKey,
            String requestId) {
        Profile profile = normalizeProfile(
                name, shortName, contactName, contactMobile, contactEmail, remark,
                merchantType, mccCode, address, latitude, longitude, shopImages);
        MerchantStatus initialStatus = status == null ? MerchantStatus.ACTIVE : status;
        String operation = "merchant:create";
        String requestDigest = DigestService.sha256(profile.digestInput() + "|" + initialStatus);
        IdempotencyStore.Claim claim = claim(
                actorId, operation, idempotencyKey, requestDigest);
        MerchantView replay = replay(claim, MerchantView.class);
        if (replay != null) {
            return replay;
        }

        // Resolve the owner identity account before inserting the merchant.
        // BD 代建 and merchant-apply approval share this single creation path.
        UUID ownerUserId = identity.resolveMerchantOwner(
                profile.contactMobile(), profile.contactName(), requestId).userId();
        walletProvisioner.register(ownerUserId);

        Instant now = clock.instant();
        UUID merchantId = UuidV7.generate(clock);
        String merchantNo = "M" + MERCHANT_NO_TIME.format(now)
                + merchantId.toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        Merchant merchant = new Merchant(
                merchantId, merchantNo, profile.name(), profile.shortName(), profile.contactName(),
                profile.contactMobile(), profile.contactEmail(), profile.remark(),
                profile.merchantType(), profile.mccCode(), profile.address(),
                profile.latitude(), profile.longitude(), profile.shopImages(),
                initialStatus, null, ownerUserId, 0, now, now);
        merchants.insert(merchant);
        MerchantView result = merchants.findView(merchantId).orElseThrow(() -> notFound(merchantId));
        audits.append(
                UuidV7.generate(clock), actorId, "MERCHANT_CREATE", "MERCHANT", merchantId,
                null, merchantDigest(merchant), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.CREATED.value(), result);
        return result;
    }

    /**
     * Creates an approved self-service merchant for the exact Identity applicant.
     * Unlike BD-assisted creation, this path must not resolve ownership again from
     * the editable contact mobile supplied in the application.
     */
    @Transactional
    public MerchantView createForApprovedApplicant(
            UUID ownerUserId,
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
            String actorId,
            String idempotencyKey,
            String requestId) {
        Profile profile = normalizeProfile(name, shortName, contactName, contactMobile,
                contactEmail, remark, merchantType, mccCode, address, latitude, longitude,
                shopImages);
        walletProvisioner.register(ownerUserId);
        String operation = "merchant:create-from-approved-apply";
        String requestDigest = DigestService.sha256(
                ownerUserId + "|" + profile.digestInput() + "|ACTIVE");
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        MerchantView replay = replay(claim, MerchantView.class);
        if (replay != null) {
            return replay;
        }

        Instant now = clock.instant();
        UUID merchantId = UuidV7.generate(clock);
        String merchantNo = "M" + MERCHANT_NO_TIME.format(now)
                + merchantId.toString().replace("-", "").substring(0, 6)
                        .toUpperCase(Locale.ROOT);
        Merchant merchant = new Merchant(merchantId, merchantNo, profile.name(),
                profile.shortName(), profile.contactName(), profile.contactMobile(),
                profile.contactEmail(), profile.remark(), profile.merchantType(),
                profile.mccCode(), profile.address(), profile.latitude(), profile.longitude(),
                profile.shopImages(), MerchantStatus.ACTIVE, null, ownerUserId, 0, now, now);
        merchants.insert(merchant);
        merchants.updateSource(merchantId, "ONBOARDING");
        MerchantView result = merchants.findView(merchantId)
                .orElseThrow(() -> notFound(merchantId));
        audits.append(UuidV7.generate(clock), actorId, "MERCHANT_CREATE_FROM_APPLY",
                "MERCHANT", merchantId, null, merchantDigest(merchant), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.CREATED.value(), result);
        return result;
    }

    /**
     * 入驻审核按「联系电话」归属商户：以申请里的联系电话解析/创建账号作为 owner，
     * 保证商户归属手机号与其联系电话一致（手机号是唯一归属键），避免商户联系电话与登录手机号错位。
     */
    @Transactional
    public MerchantView createForApprovedApplicantByMobile(
            String contactMobile,
            String contactName,
            String name,
            String shortName,
            String contactEmail,
            String remark,
            MerchantType merchantType,
            String mccCode,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String shopImages,
            String actorId,
            String idempotencyKey,
            String requestId) {
        UUID ownerUserId = identity.resolveMerchantOwner(contactMobile, contactName, requestId).userId();
        return createForApprovedApplicant(ownerUserId, name, shortName, contactName, contactMobile,
                contactEmail, remark, merchantType, mccCode, address, latitude, longitude, shopImages,
                actorId, idempotencyKey, requestId);
    }

    @Transactional
    public MerchantView update(
            UUID merchantId,
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
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        Profile profile = normalizeProfile(
                name, shortName, contactName, contactMobile, contactEmail, remark,
                merchantType, mccCode, address, latitude, longitude, shopImages);
        String operation = "merchant:update:" + merchantId;
        String requestDigest = DigestService.sha256(profile.digestInput() + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(
                actorId, operation, idempotencyKey, requestDigest);
        MerchantView replay = replay(claim, MerchantView.class);
        if (replay != null) {
            return replay;
        }

        Merchant before = merchants.find(merchantId).orElseThrow(() -> notFound(merchantId));
        Instant now = clock.instant();
        Merchant after = before.updateProfile(
                profile.name(), profile.shortName(), profile.contactName(), profile.contactMobile(),
                profile.contactEmail(), profile.remark(), profile.merchantType(),
                profile.mccCode(), profile.address(), profile.latitude(), profile.longitude(),
                profile.shopImages(), now);
        if (!merchants.updateProfile(after, expectedVersion)) {
            throw versionConflict();
        }
        MerchantView result = merchants.findView(merchantId).orElseThrow(() -> notFound(merchantId));
        audits.append(
                UuidV7.generate(clock), actorId, "MERCHANT_UPDATE", "MERCHANT", merchantId,
                merchantDigest(before), merchantDigest(after), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public MerchantView changeStatus(
            UUID merchantId,
            MerchantStatus target,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "merchant:" + target.name().toLowerCase(Locale.ROOT) + ":" + merchantId;
        String requestDigest = DigestService.sha256(target + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(
                actorId, operation, idempotencyKey, requestDigest);
        MerchantView replay = replay(claim, MerchantView.class);
        if (replay != null) {
            return replay;
        }

        Merchant before = merchants.find(merchantId).orElseThrow(() -> notFound(merchantId));
        Instant now = clock.instant();
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        if (before.status() != target
                && !merchants.updateStatus(merchantId, target, expectedVersion, now)) {
            throw versionConflict();
        }
        Merchant after = before.changeStatus(target, now);
        MerchantView result = merchants.findView(merchantId).orElseThrow(() -> notFound(merchantId));
        audits.append(
                UuidV7.generate(clock), actorId,
                switch (target) {
                    case ACTIVE -> "MERCHANT_ENABLE";
                    case DISABLED -> "MERCHANT_DISABLE";
                    case FROZEN -> "MERCHANT_FREEZE";
                },
                "MERCHANT", merchantId, merchantDigest(before), merchantDigest(after), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public MerchantView freeze(
            UUID merchantId,
            String reason,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "merchant:freeze:" + merchantId;
        String requestDigest = DigestService.sha256(reason + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(
                actorId, operation, idempotencyKey, requestDigest);
        MerchantView replay = replay(claim, MerchantView.class);
        if (replay != null) {
            return replay;
        }

        Merchant before = merchants.find(merchantId).orElseThrow(() -> notFound(merchantId));
        Instant now = clock.instant();
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        if (before.status() != MerchantStatus.ACTIVE) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_NOT_FREEZABLE",
                    "Only an active merchant can be frozen");
        }
        if (!merchants.updateFreeze(merchantId, MerchantStatus.FROZEN, reason,
                expectedVersion, now)) {
            throw versionConflict();
        }
        Merchant after = before.freeze(reason, now);
        MerchantView result = merchants.findView(merchantId).orElseThrow(() -> notFound(merchantId));
        audits.append(
                UuidV7.generate(clock), actorId, "MERCHANT_FREEZE", "MERCHANT", merchantId,
                merchantDigest(before), merchantDigest(after), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public MerchantView unfreeze(
            UUID merchantId,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "merchant:unfreeze:" + merchantId;
        String requestDigest = DigestService.sha256(Long.toString(expectedVersion));
        IdempotencyStore.Claim claim = claim(
                actorId, operation, idempotencyKey, requestDigest);
        MerchantView replay = replay(claim, MerchantView.class);
        if (replay != null) {
            return replay;
        }

        Merchant before = merchants.find(merchantId).orElseThrow(() -> notFound(merchantId));
        Instant now = clock.instant();
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        if (before.status() != MerchantStatus.FROZEN) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_NOT_UNFREEZABLE",
                    "Only a frozen merchant can be unfrozen");
        }
        if (!merchants.updateFreeze(merchantId, MerchantStatus.ACTIVE, null,
                expectedVersion, now)) {
            throw versionConflict();
        }
        Merchant after = before.unfreeze(now);
        MerchantView result = merchants.findView(merchantId).orElseThrow(() -> notFound(merchantId));
        audits.append(
                UuidV7.generate(clock), actorId, "MERCHANT_UNFREEZE", "MERCHANT", merchantId,
                merchantDigest(before), merchantDigest(after), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public void delete(
            UUID merchantId,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "merchant:delete:" + merchantId;
        String requestDigest = DigestService.sha256(Long.toString(expectedVersion));
        IdempotencyStore.Claim claim = claim(
                actorId, operation, idempotencyKey, requestDigest);
        if (claim.responseJson().isPresent()) {
            return;
        }

        Merchant before = merchants.find(merchantId).orElseThrow(() -> notFound(merchantId));
        MerchantStore.Dependencies dependencies = merchants.dependencies(merchantId);
        if (dependencies.exists()) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_HAS_DEPENDENCIES",
                    "Merchant has applications or transactions and cannot be deleted");
        }
        if (!merchants.delete(merchantId, expectedVersion)) {
            throw versionConflict();
        }
        Instant now = clock.instant();
        audits.append(
                UuidV7.generate(clock), actorId, "MERCHANT_DELETE", "MERCHANT", merchantId,
                merchantDigest(before), null, requestId, now);
        idempotency.complete(actorId, operation, idempotencyKey,
                HttpStatus.NO_CONTENT.value(), "{}", now);
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

    private <T> T replay(IdempotencyStore.Claim claim, Class<T> type) {
        if (claim.responseJson().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(claim.responseJson().orElseThrow(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    private void complete(
            String actorId, String operation, String key, int status, MerchantView response) {
        try {
            idempotency.complete(actorId, operation, key, status,
                    objectMapper.writeValueAsString(response), clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize idempotency response", exception);
        }
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.length() < 2 || normalized.length() > 64) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_MERCHANT_NAME",
                    "Merchant name must contain between 2 and 64 characters");
        }
        return normalized;
    }

    private static Profile normalizeProfile(
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
            String shopImages) {
        String normalizedName = normalizeName(name);
        String normalizedShortName = normalizeRequired(
                shortName, 2, 32, "INVALID_MERCHANT_SHORT_NAME", "Merchant short name");
        String normalizedContactName = normalizeRequired(
                contactName, 2, 64, "INVALID_CONTACT_NAME", "Contact name");
        String normalizedMobile = contactMobile == null ? "" : contactMobile.trim();
        if (!MOBILE.matcher(normalizedMobile).matches()) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_CONTACT_MOBILE",
                    "Contact mobile must be a valid mainland China mobile number");
        }
        String normalizedEmail = normalizeOptional(contactEmail, 254, "INVALID_CONTACT_EMAIL");
        if (normalizedEmail != null && !EMAIL.matcher(normalizedEmail).matches()) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_CONTACT_EMAIL",
                    "Contact email is invalid");
        }
        String normalizedRemark = normalizeOptional(remark, 500, "INVALID_MERCHANT_REMARK");
        MerchantType normalizedType = merchantType == null
                ? MerchantType.PERSONAL : merchantType;
        String normalizedMcc = normalizeOptional(mccCode, 16, "INVALID_MCC_CODE");
        String normalizedAddress = normalizeOptional(address, 200, "INVALID_MERCHANT_ADDRESS");
        BigDecimal normalizedLatitude = normalizeLatitude(latitude);
        BigDecimal normalizedLongitude = normalizeLongitude(longitude);
        String normalizedImages = normalizeOptional(shopImages, 1000, "INVALID_SHOP_IMAGES");
        return new Profile(normalizedName, normalizedShortName, normalizedContactName,
                normalizedMobile, normalizedEmail, normalizedRemark, normalizedType,
                normalizedMcc, normalizedAddress, normalizedLatitude, normalizedLongitude,
                normalizedImages);
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
            String value, int min, int max, String code, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, code,
                    label + " must contain between " + min + " and " + max + " characters");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int max, String code) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, code,
                    "Value must not exceed " + max + " characters");
        }
        return normalized;
    }

    private static String merchantDigest(Merchant merchant) {
        return DigestService.sha256(merchant.merchantId() + "|" + merchant.merchantNo() + "|"
                + merchant.name() + "|" + merchant.shortName() + "|" + merchant.contactName()
                + "|" + merchant.contactMobile() + "|" + merchant.contactEmail() + "|"
                + merchant.remark() + "|" + merchant.merchantType() + "|" + merchant.mccCode()
                + "|" + merchant.address() + "|" + merchant.latitude() + "|"
                + merchant.longitude() + "|" + merchant.shopImages() + "|"
                + merchant.status() + "|" + merchant.freezeReason() + "|" + merchant.version());
    }

    private record Profile(
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
            String shopImages) {
        String digestInput() {
            return String.join("|", name, shortName, contactName, contactMobile,
                    contactEmail == null ? "" : contactEmail,
                    remark == null ? "" : remark,
                    merchantType == null ? "" : merchantType.name(),
                    mccCode == null ? "" : mccCode,
                    address == null ? "" : address,
                    latitude == null ? "" : latitude.toPlainString(),
                    longitude == null ? "" : longitude.toPlainString(),
                    shopImages == null ? "" : shopImages);
        }
    }

    private static OpsBusinessException notFound(UUID merchantId) {
        return new OpsBusinessException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND",
                "Merchant " + merchantId + " was not found");
    }

    private static OpsBusinessException versionConflict() {
        return new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_VERSION_CONFLICT",
                "Merchant was changed by another operator; reload and retry");
    }
}
