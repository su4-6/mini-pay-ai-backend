package com.minipay.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.payment.application.port.ApplicationStore;
import com.minipay.payment.application.port.ApplicationStore.ApplicationPage;
import com.minipay.payment.application.port.ApplicationStore.ApplicationView;
import com.minipay.payment.application.port.IdempotencyStore;
import com.minipay.payment.application.port.MerchantStore;
import com.minipay.payment.application.port.OperationAuditStore;
import com.minipay.payment.domain.model.ApplicationStatus;
import com.minipay.payment.domain.model.MerchantApplication;
import com.minipay.payment.domain.model.MerchantStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationManagementService {
    private final ApplicationStore applications;
    private final MerchantStore merchants;
    private final IdempotencyStore idempotency;
    private final OperationAuditStore audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApplicationManagementService(
            ApplicationStore applications,
            MerchantStore merchants,
            IdempotencyStore idempotency,
            OperationAuditStore audits,
            ObjectMapper objectMapper,
            Clock clock) {
        this.applications = applications;
        this.merchants = merchants;
        this.idempotency = idempotency;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApplicationPage list(
            int page,
            int size,
            String appId,
            String name,
            UUID merchantId,
            ApplicationStatus status,
            Boolean unavailable) {
        if (page < 0 || size < 1 || size > 100) {
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page must be non-negative and size must be between 1 and 100");
        }
        return applications.findPage(page, size,
                normalizeOptional(appId, 64, "INVALID_APPLICATION_APP_ID"),
                normalizeOptional(name, 64, "INVALID_APPLICATION_NAME"), merchantId, status,
                unavailable);
    }

    @Transactional(readOnly = true)
    public ApplicationStore.ApplicationSummary summary() {
        return applications.getSummary();
    }

    @Transactional(readOnly = true)
    public ApplicationView get(UUID applicationId) {
        return applications.findApplicationView(applicationId)
                .orElseThrow(() -> notFound(applicationId));
    }

    @Transactional
    public ApplicationView create(
            UUID merchantId,
            String name,
            ApplicationStatus status,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String normalizedName = normalizeName(name);
        ApplicationStatus initialStatus = status == null ? ApplicationStatus.ACTIVE : status;
        String operation = "application:create";
        String requestDigest = DigestService.sha256(
                merchantId + "|" + normalizedName + "|" + initialStatus);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplicationView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        var merchant = merchants.find(merchantId).orElseThrow(() -> merchantNotFound(merchantId));
        if (initialStatus == ApplicationStatus.ACTIVE
                && merchant.status() != MerchantStatus.ACTIVE) {
            throw merchantNotActive(merchant.status());
        }
        ensureNameAvailable(merchantId, normalizedName, null);

        Instant now = clock.instant();
        UUID applicationId = UuidV7.generate(clock);
        String appId = "mp_app_" + applicationId.toString().replace("-", "");
        MerchantApplication application = new MerchantApplication(
                applicationId, appId, merchantId, normalizedName, initialStatus, 0, now, now);
        try {
            applications.insert(application);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        ApplicationView result = applications.findApplicationView(applicationId)
                .orElseThrow(() -> notFound(applicationId));
        audits.append(UuidV7.generate(clock), actorId, "APPLICATION_CREATE", "APPLICATION",
                applicationId, null, digest(application), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.CREATED.value(), result);
        return result;
    }

    @Transactional
    public ApplicationView update(
            UUID applicationId,
            String name,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String normalizedName = normalizeName(name);
        String operation = "application:update:" + applicationId;
        String requestDigest = DigestService.sha256(normalizedName + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplicationView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        MerchantApplication before = applications.findApplication(applicationId)
                .orElseThrow(() -> notFound(applicationId));
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        ensureNameAvailable(before.merchantId(), normalizedName, applicationId);
        Instant now = clock.instant();
        MerchantApplication after = before.rename(normalizedName, now);
        try {
            if (!applications.updateName(after, expectedVersion)) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        ApplicationView result = applications.findApplicationView(applicationId)
                .orElseThrow(() -> notFound(applicationId));
        audits.append(UuidV7.generate(clock), actorId, "APPLICATION_UPDATE", "APPLICATION",
                applicationId, digest(before), digest(after), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public ApplicationView changeStatus(
            UUID applicationId,
            ApplicationStatus target,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "application:" + target.name().toLowerCase(Locale.ROOT)
                + ":" + applicationId;
        String requestDigest = DigestService.sha256(target + "|" + expectedVersion);
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        ApplicationView replay = replay(claim);
        if (replay != null) {
            return replay;
        }

        MerchantApplication before = applications.findApplication(applicationId)
                .orElseThrow(() -> notFound(applicationId));
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        if (target == ApplicationStatus.ACTIVE) {
            var merchant = merchants.find(before.merchantId())
                    .orElseThrow(() -> merchantNotFound(before.merchantId()));
            if (merchant.status() != MerchantStatus.ACTIVE) {
                throw merchantNotActive(merchant.status());
            }
        }
        Instant now = clock.instant();
        if (before.status() != target
                && !applications.updateStatus(applicationId, target, expectedVersion, now)) {
            throw versionConflict();
        }
        MerchantApplication after = before.changeStatus(target, now);
        ApplicationView result = applications.findApplicationView(applicationId)
                .orElseThrow(() -> notFound(applicationId));
        audits.append(UuidV7.generate(clock), actorId,
                target == ApplicationStatus.ACTIVE ? "APPLICATION_ENABLE" : "APPLICATION_DISABLE",
                "APPLICATION", applicationId, digest(before), digest(after), requestId, now);
        complete(actorId, operation, idempotencyKey, HttpStatus.OK.value(), result);
        return result;
    }

    @Transactional
    public void delete(
            UUID applicationId,
            long expectedVersion,
            String actorId,
            String idempotencyKey,
            String requestId) {
        String operation = "application:delete:" + applicationId;
        String requestDigest = DigestService.sha256(Long.toString(expectedVersion));
        IdempotencyStore.Claim claim = claim(actorId, operation, idempotencyKey, requestDigest);
        if (claim.responseJson().isPresent()) {
            return;
        }

        MerchantApplication before = applications.findApplication(applicationId)
                .orElseThrow(() -> notFound(applicationId));
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        if (before.status() != ApplicationStatus.DISABLED) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_MUST_BE_DISABLED",
                    "Application must be disabled before deletion");
        }
        ApplicationStore.Dependencies dependencies = applications.dependencies(
                applicationId, before.appId());
        if (dependencies.hasTransactions()) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_HAS_TRANSACTIONS",
                    "Application has transactions and cannot be deleted");
        }
        if (dependencies.hasReferences()) {
            throw new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_HAS_DEPENDENCIES",
                    "Application has retained business references and cannot be deleted");
        }
        if (!applications.deleteApplication(applicationId, expectedVersion)) {
            throw versionConflict();
        }
        Instant now = clock.instant();
        audits.append(UuidV7.generate(clock), actorId, "APPLICATION_DELETE", "APPLICATION",
                applicationId, digest(before), null, requestId, now);
        idempotency.complete(actorId, operation, idempotencyKey,
                HttpStatus.NO_CONTENT.value(), "{}", now);
    }

    private void ensureNameAvailable(UUID merchantId, String name, UUID excludingApplicationId) {
        if (applications.nameExists(merchantId, name, excludingApplicationId)) {
            throw nameConflict();
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

    private ApplicationView replay(IdempotencyStore.Claim claim) {
        if (claim.responseJson().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(claim.responseJson().orElseThrow(), ApplicationView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    private void complete(
            String actorId, String operation, String key, int status, ApplicationView response) {
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
            throw new OpsBusinessException(HttpStatus.BAD_REQUEST, "INVALID_APPLICATION_NAME",
                    "Application name must contain between 2 and 64 characters");
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

    private static String digest(MerchantApplication application) {
        return DigestService.sha256(application.applicationId() + "|" + application.appId() + "|"
                + application.merchantId() + "|" + application.name() + "|"
                + application.status() + "|" + application.version());
    }

    private static OpsBusinessException notFound(UUID applicationId) {
        return new OpsBusinessException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                "Application " + applicationId + " was not found");
    }

    private static OpsBusinessException merchantNotFound(UUID merchantId) {
        return new OpsBusinessException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND",
                "Merchant " + merchantId + " was not found");
    }

    private static OpsBusinessException merchantNotActive(MerchantStatus status) {
        return new OpsBusinessException(HttpStatus.CONFLICT, "MERCHANT_NOT_ACTIVE",
                "An active application requires an active merchant (current: " + status + ")");
    }

    private static OpsBusinessException nameConflict() {
        return new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_NAME_CONFLICT",
                "An application with this name already exists for the merchant");
    }

    private static OpsBusinessException versionConflict() {
        return new OpsBusinessException(HttpStatus.CONFLICT, "APPLICATION_VERSION_CONFLICT",
                "Application was changed by another operator; reload and retry");
    }
}
