package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.port.ApplicationApplyStore;
import com.minipay.payment.application.port.ApplicationStore;
import com.minipay.payment.application.port.ApplicationStore.ApplicationPage;
import com.minipay.payment.application.port.ApplicationStore.ApplicationView;
import com.minipay.payment.application.port.MerchantApplyStore.ApplyPage;
import com.minipay.payment.application.port.MerchantApplyStore.ApplyView;
import com.minipay.payment.application.port.MerchantStore.MerchantPage;
import com.minipay.payment.application.port.MerchantStore.MerchantView;
import com.minipay.payment.application.service.ApplicationApplyService;
import com.minipay.payment.application.service.ApplicationManagementService;
import com.minipay.payment.application.service.DashboardQueryService;
import com.minipay.payment.application.service.DashboardQueryService.DashboardResponse;
import com.minipay.payment.application.service.ImageStorageService;
import com.minipay.payment.application.service.MerchantApplyService;
import com.minipay.payment.application.service.MerchantManagementService;
import com.minipay.payment.application.service.OpsBusinessException;
import com.minipay.payment.domain.model.ApplicationApplyStatus;
import com.minipay.payment.domain.model.ApplicationStatus;
import com.minipay.payment.domain.model.MerchantApplyStatus;
import com.minipay.payment.domain.model.MerchantStatus;
import com.minipay.payment.domain.model.MerchantType;
import com.minipay.payment.infrastructure.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {
    private final MerchantManagementService merchants;
    private final MerchantApplyService merchantApplies;
    private final ApplicationManagementService applications;
    private final ApplicationApplyService applicationApplies;
    private final DashboardQueryService dashboard;
    private final ImageStorageService imageStorage;

    public OpsController(
            MerchantManagementService merchants,
            MerchantApplyService merchantApplies,
            ApplicationManagementService applications,
            ApplicationApplyService applicationApplies,
            DashboardQueryService dashboard,
            ImageStorageService imageStorage) {
        this.merchants = merchants;
        this.merchantApplies = merchantApplies;
        this.applications = applications;
        this.applicationApplies = applicationApplies;
        this.dashboard = dashboard;
        this.imageStorage = imageStorage;
    }

    @GetMapping("/dashboard")
    DashboardResponse dashboard(@RequestParam(defaultValue = "7d") String range) {
        return dashboard.get(range);
    }

    @GetMapping("/merchants")
    MerchantPage merchants(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String contactMobile,
            @RequestParam(required = false) MerchantStatus status) {
        return merchants.list(page, size, merchantNo, name, contactMobile, status);
    }

    @PostMapping("/merchants")
    ResponseEntity<MerchantView> createMerchant(
            @Valid @RequestBody CreateMerchantRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        MerchantView created = merchants.create(
                body.name(), body.shortName(), body.contactName(), body.contactMobile(),
                body.contactEmail(), body.remark(), body.merchantType(), body.mccCode(),
                body.address(), body.latitude(), body.longitude(), body.shopImages(),
                body.status(), authentication.getName(), idempotencyKey,
                RequestIdFilter.get(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Long.toString(created.version()))
                .body(created);
    }

    @GetMapping("/merchants/{merchantId}")
    ResponseEntity<MerchantView> getMerchant(@PathVariable UUID merchantId) {
        MerchantView merchant = merchants.get(merchantId);
        return ResponseEntity.ok().eTag(Long.toString(merchant.version())).body(merchant);
    }

    @PostMapping("/image-uploads")
    ImageStorageService.UploadGrant createImageUpload(
            @Valid @RequestBody ImageUploadRequest body) {
        return imageStorage.createUpload(
                body.fileName(), body.contentType(), body.sizeBytes(), body.sha256());
    }

    @PostMapping("/image-read-urls")
    ImageReadUrlsResponse readImageUrls(@Valid @RequestBody ImageReadUrlsRequest body) {
        return new ImageReadUrlsResponse(imageStorage.readUrls(body.objectKeys()));
    }

    @PatchMapping("/merchants/{merchantId}")
    ResponseEntity<MerchantView> updateMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody UpdateMerchantRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        MerchantView updated = merchants.update(
                merchantId, body.name(), body.shortName(), body.contactName(), body.contactMobile(),
                body.contactEmail(), body.remark(), body.merchantType(), body.mccCode(),
                body.address(), body.latitude(), body.longitude(), body.shopImages(),
                body.version(), authentication.getName(), idempotencyKey,
                RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/merchants/{merchantId}/enable")
    ResponseEntity<MerchantView> enableMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return changedStatus(merchantId, MerchantStatus.ACTIVE, body.version(),
                authentication, idempotencyKey, request);
    }

    @PostMapping("/merchants/{merchantId}/disable")
    ResponseEntity<MerchantView> disableMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return changedStatus(merchantId, MerchantStatus.DISABLED, body.version(),
                authentication, idempotencyKey, request);
    }

    @PostMapping("/merchants/{merchantId}/freeze")
    ResponseEntity<MerchantView> freezeMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody FreezeRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        MerchantView updated = merchants.freeze(
                merchantId, body.reason(), body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/merchants/{merchantId}/unfreeze")
    ResponseEntity<MerchantView> unfreezeMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        MerchantView updated = merchants.unfreeze(
                merchantId, body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @DeleteMapping("/merchants/{merchantId}")
    ResponseEntity<Void> deleteMerchant(
            @PathVariable UUID merchantId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        merchants.delete(merchantId, parseVersion(ifMatch), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/merchant-applies")
    ApplyPage merchantApplies(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) MerchantApplyStatus applyStatus,
            @RequestParam(required = false) UUID userId) {
        return merchantApplies.list(page, size, applyStatus, userId);
    }

    @GetMapping("/merchant-applies/{applyId}")
    ApplyView getMerchantApply(@PathVariable long applyId) {
        return merchantApplies.get(applyId);
    }

    @PostMapping("/merchant-applies/{applyId}/approve")
    ResponseEntity<ApplyView> approveMerchantApply(
            @PathVariable long applyId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplyView updated = merchantApplies.approve(
                applyId, body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/merchant-applies/{applyId}/reject")
    ResponseEntity<ApplyView> rejectMerchantApply(
            @PathVariable long applyId,
            @Valid @RequestBody ApplyRejectRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplyView updated = merchantApplies.reject(
                applyId, body.reason(), body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/merchant-applies/{applyId}/request-supplement")
    ResponseEntity<ApplyView> requestSupplementMerchantApply(
            @PathVariable long applyId,
            @Valid @RequestBody ApplyRejectRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplyView updated = merchantApplies.requestSupplement(
                applyId, body.reason(), body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @GetMapping("/application-applies")
    ApplicationApplyStore.ApplyPage applicationApplies(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) ApplicationApplyStatus applyStatus) {
        return applicationApplies.list(page, size, userId, applyStatus);
    }

    @GetMapping("/application-applies/{applyId}")
    ApplicationApplyStore.ApplyView getApplicationApply(@PathVariable long applyId) {
        return applicationApplies.get(applyId);
    }

    @PostMapping("/application-applies")
    ResponseEntity<ApplicationApplyStore.ApplyView> submitApplicationApply(
            @Valid @RequestBody SubmitApplicationApplyRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationApplyStore.ApplyView created = applicationApplies.submit(
                body.merchantId(), body.name(), requireUserId(authentication),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Long.toString(created.version()))
                .body(created);
    }

    @PostMapping("/application-applies/{applyId}/submit")
    ResponseEntity<ApplicationApplyStore.ApplyView> resubmitApplicationApply(
            @PathVariable long applyId,
            @Valid @RequestBody ResubmitApplicationApplyRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationApplyStore.ApplyView updated = applicationApplies.resubmit(
                applyId, body.name(), body.version(), requireUserId(authentication),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/application-applies/{applyId}/approve")
    ResponseEntity<ApplicationApplyStore.ApplyView> approveApplicationApply(
            @PathVariable long applyId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationApplyStore.ApplyView updated = applicationApplies.approve(
                applyId, body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/application-applies/{applyId}/reject")
    ResponseEntity<ApplicationApplyStore.ApplyView> rejectApplicationApply(
            @PathVariable long applyId,
            @Valid @RequestBody ApplyRejectRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationApplyStore.ApplyView updated = applicationApplies.reject(
                applyId, body.reason(), body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/application-applies/{applyId}/request-supplement")
    ResponseEntity<ApplicationApplyStore.ApplyView> requestSupplementApplicationApply(
            @PathVariable long applyId,
            @Valid @RequestBody ApplyRejectRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationApplyStore.ApplyView updated = applicationApplies.requestSupplement(
                applyId, body.reason(), body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @GetMapping("/applications")
    ApplicationPage applications(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) Boolean unavailable) {
        return applications.list(page, size, appId, name, merchantId, status, unavailable);
    }

    @GetMapping("/applications/summary")
    ApplicationStore.ApplicationSummary applicationSummary() {
        return applications.summary();
    }

    @PostMapping("/applications")
    ResponseEntity<ApplicationView> createApplication(
            @Valid @RequestBody CreateApplicationRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationView created = applications.create(
                body.merchantId(), body.name(), body.status(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Long.toString(created.version()))
                .body(created);
    }

    @GetMapping("/applications/{applicationId}")
    ResponseEntity<ApplicationView> getApplication(@PathVariable UUID applicationId) {
        ApplicationView application = applications.get(applicationId);
        return ResponseEntity.ok().eTag(Long.toString(application.version())).body(application);
    }

    @PatchMapping("/applications/{applicationId}")
    ResponseEntity<ApplicationView> updateApplication(
            @PathVariable UUID applicationId,
            @Valid @RequestBody UpdateApplicationRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        ApplicationView updated = applications.update(
                applicationId, body.name(), body.version(), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    @PostMapping("/applications/{applicationId}/enable")
    ResponseEntity<ApplicationView> enableApplication(
            @PathVariable UUID applicationId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return changedApplicationStatus(applicationId, ApplicationStatus.ACTIVE, body.version(),
                authentication, idempotencyKey, request);
    }

    @PostMapping("/applications/{applicationId}/disable")
    ResponseEntity<ApplicationView> disableApplication(
            @PathVariable UUID applicationId,
            @Valid @RequestBody VersionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        return changedApplicationStatus(applicationId, ApplicationStatus.DISABLED, body.version(),
                authentication, idempotencyKey, request);
    }

    @DeleteMapping("/applications/{applicationId}")
    ResponseEntity<Void> deleteApplication(
            @PathVariable UUID applicationId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        applications.delete(applicationId, parseVersion(ifMatch), authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<MerchantView> changedStatus(
            UUID merchantId,
            MerchantStatus status,
            long version,
            JwtAuthenticationToken authentication,
            String idempotencyKey,
            HttpServletRequest request) {
        MerchantView updated = merchants.changeStatus(
                merchantId, status, version, authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    private ResponseEntity<ApplicationView> changedApplicationStatus(
            UUID applicationId,
            ApplicationStatus status,
            long version,
            JwtAuthenticationToken authentication,
            String idempotencyKey,
            HttpServletRequest request) {
        ApplicationView updated = applications.changeStatus(
                applicationId, status, version, authentication.getName(),
                idempotencyKey, RequestIdFilter.get(request));
        return ResponseEntity.ok().eTag(Long.toString(updated.version())).body(updated);
    }

    /**
     * 提交/重提的申请人取自 JWT subject（merchant-web 的商户拥有者账户，UUID）。
     */
    private static UUID requireUserId(JwtAuthenticationToken authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new OpsBusinessException(HttpStatus.UNAUTHORIZED, "INVALID_USER_SUBJECT",
                    "JWT subject is not a valid user id");
        }
    }

    private static long parseVersion(String ifMatch) {
        String normalized = ifMatch.trim().replace("W/", "").replace("\"", "");
        try {
            long version = Long.parseLong(normalized);
            if (version < 0) {
                throw new NumberFormatException("negative version");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain a non-negative version");
        }
    }

    public record CreateMerchantRequest(
            @NotBlank @Size(min = 2, max = 64) String name,
            @NotBlank @Size(min = 2, max = 32) String shortName,
            @NotBlank @Size(min = 2, max = 64) String contactName,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String contactMobile,
            @Email @Size(max = 254) String contactEmail,
            @Size(max = 500) String remark,
            @NotNull MerchantType merchantType,
            @Size(max = 16) String mccCode,
            @Size(max = 200) String address,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Size(max = 1000) String shopImages,
            MerchantStatus status) {
    }

    public record UpdateMerchantRequest(
            @NotBlank @Size(min = 2, max = 64) String name,
            @NotBlank @Size(min = 2, max = 32) String shortName,
            @NotBlank @Size(min = 2, max = 64) String contactName,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String contactMobile,
            @Email @Size(max = 254) String contactEmail,
            @Size(max = 500) String remark,
            @NotNull MerchantType merchantType,
            @Size(max = 16) String mccCode,
            @Size(max = 200) String address,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Size(max = 1000) String shopImages,
            @NotNull @Min(0) Long version) {
    }

    public record ImageUploadRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank String contentType,
            @Min(1) @Max(5_242_880) long sizeBytes,
            @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) {
    }

    public record ImageReadUrlsRequest(
            @NotEmpty @Size(max = 20) List<@NotBlank String> objectKeys) {
    }

    public record ImageReadUrlsResponse(java.util.Map<String, String> urls) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record FreezeRequest(
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record ApplyRejectRequest(
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record CreateApplicationRequest(
            @NotNull UUID merchantId,
            @NotBlank @Size(min = 2, max = 64) String name,
            ApplicationStatus status) {
    }

    public record UpdateApplicationRequest(
            @NotBlank @Size(min = 2, max = 64) String name,
            @NotNull @Min(0) Long version) {
    }

    public record SubmitApplicationApplyRequest(
            @NotNull UUID merchantId,
            @NotBlank @Size(min = 2, max = 64) String name) {
    }

    public record ResubmitApplicationApplyRequest(
            @NotBlank @Size(min = 2, max = 64) String name,
            @NotNull @Min(0) Long version) {
    }
}
