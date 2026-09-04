package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.port.ApplicationApplyStore.ApplyView;
import com.minipay.payment.application.port.MerchantApplyStore.ApplyPage;
import com.minipay.payment.application.service.ApplicationApplyService;
import com.minipay.payment.application.service.ImageStorageService;
import com.minipay.payment.application.service.MerchantApplyService;
import com.minipay.payment.application.service.MerchantInitializationService;
import com.minipay.payment.application.service.MerchantService;
import com.minipay.payment.application.service.PaymentProblemException;
import com.minipay.payment.domain.model.MerchantType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Merchant B-end API. Every merchant resource is checked against the JWT owner. */
@RestController
@RequestMapping("/api/v1/merchant")
public class MerchantController {
    private final MerchantService merchants;
    private final MerchantApplyService onboardings;
    private final ApplicationApplyService applicationApplies;
    private final ImageStorageService imageStorage;
    private final MerchantInitializationService initialization;

    public MerchantController(
            MerchantService merchants,
            MerchantApplyService onboardings,
            ApplicationApplyService applicationApplies,
            ImageStorageService imageStorage,
            MerchantInitializationService initialization) {
        this.merchants = merchants;
        this.onboardings = onboardings;
        this.applicationApplies = applicationApplies;
        this.imageStorage = imageStorage;
        this.initialization = initialization;
    }

    @GetMapping("/merchants")
    public List<MerchantService.MerchantView> merchants(@AuthenticationPrincipal Jwt jwt) {
        return merchants.merchants(subject(jwt));
    }

    @GetMapping("/merchants/{merchantId}/profile")
    public MerchantService.MerchantView profile(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID merchantId) {
        return merchants.merchant(subject(jwt), merchantId);
    }

    @PatchMapping("/merchants/{merchantId}/profile")
    public MerchantService.MerchantView updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MerchantProfileRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.updateProfile(subject(jwt), merchantId, request.version(),
                request.shortName(), request.mccCode(), request.address(), request.latitude(),
                request.longitude(), request.shopImages(), request.contactName(),
                request.contactMobile(), request.contactEmail(), request.remark());
    }

    @GetMapping("/onboardings")
    public ApplyPage onboardings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return onboardings.listForOwner(subject(jwt), page, size);
    }

    @PostMapping("/onboardings")
    @ResponseStatus(HttpStatus.CREATED)
    public com.minipay.payment.application.port.MerchantApplyStore.ApplyView submitOnboarding(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody OnboardingRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return onboardings.submit(subject(jwt), request.merchantType(), request.shopName(),
                request.mccCode(), request.address(), request.latitude(), request.longitude(),
                request.shopImages(), request.contactName(), request.contactMobile(),
                request.contactEmail(), request.remark(), idempotencyKey, requestId(requestId));
    }

    /** 商户侧店铺图片上传：签发预签名 PUT URL，前端直传 OSS。 */
    @PostMapping("/image-uploads")
    public ImageStorageService.UploadGrant createImageUpload(
            @Valid @RequestBody OpsController.ImageUploadRequest body) {
        return imageStorage.createUpload(
                body.fileName(), body.contentType(), body.sizeBytes(), body.sha256());
    }

    /** 商户侧店铺图片读取：批量签发限时 GET URL。 */
    @PostMapping("/image-read-urls")
    public OpsController.ImageReadUrlsResponse readImageUrls(
            @Valid @RequestBody OpsController.ImageReadUrlsRequest body) {
        return new OpsController.ImageReadUrlsResponse(imageStorage.readUrls(body.objectKeys()));
    }

    @PutMapping("/onboardings/{applyId}")
    public com.minipay.payment.application.port.MerchantApplyStore.ApplyView resubmitOnboarding(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long applyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody OnboardingResubmitRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return onboardings.resubmit(subject(jwt), applyId, request.version(),
                request.merchantType(), request.shopName(), request.mccCode(), request.address(),
                request.latitude(), request.longitude(), request.shopImages(),
                request.contactName(), request.contactMobile(), request.contactEmail(),
                request.remark(), idempotencyKey, requestId(requestId));
    }

    @PostMapping("/merchants/{merchantId}/initialization")
    public MerchantService.InitializationView initialize(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return initialization.initialize(subject(jwt), merchantId);
    }

    @GetMapping("/merchants/{merchantId}/dashboard")
    public MerchantService.DashboardView dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "7") int days) {
        return merchants.dashboard(subject(jwt), merchantId, days);
    }

    @GetMapping("/merchants/{merchantId}/applications")
    public List<MerchantService.ApplicationView> applications(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID merchantId) {
        return merchants.applications(subject(jwt), merchantId);
    }

    @GetMapping("/merchants/{merchantId}/applications/{appId}")
    public MerchantService.ApplicationView application(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId) {
        return merchants.application(subject(jwt), merchantId, appId);
    }

    @PostMapping("/merchants/{merchantId}/application-applies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplyView applyForApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody ApplicationApplyRequest request) {
        requireIdempotencyKey(idempotencyKey);
        merchants.merchant(subject(jwt), merchantId);
        return applicationApplies.submit(merchantId, request.name(), subject(jwt), idempotencyKey,
                requestId(requestId));
    }

    @PatchMapping("/merchants/{merchantId}/applications/{appId}")
    public MerchantService.ApplicationView configureApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ApplicationRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.updateApplication(subject(jwt), merchantId, appId, request.version(),
                request.toInput());
    }

    @PutMapping("/merchants/{merchantId}/applications/{appId}/status")
    public MerchantService.ApplicationView setApplicationStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StatusRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.setApplicationStatus(subject(jwt), merchantId, appId,
                request.version(), request.enabled());
    }

    @PostMapping("/merchants/{merchantId}/applications/{appId}/secret-view")
    public MerchantService.ApplicationSecretView viewApplicationSecret(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.viewApplicationSecret(subject(jwt), merchantId, appId);
    }

    @PostMapping("/merchants/{merchantId}/applications/{appId}/secret-reset")
    public MerchantService.ApplicationSecretView resetApplicationSecret(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.resetApplicationSecret(
                subject(jwt), merchantId, appId, request.version());
    }

    @GetMapping("/merchants/{merchantId}/collection-codes")
    public List<ApplicationCollectionCode> collectionCodes(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID merchantId) {
        return merchants.applications(subject(jwt), merchantId).stream()
                .map(application -> new ApplicationCollectionCode(application,
                        merchants.collectionCode(subject(jwt), merchantId, application.appId())))
                .toList();
    }

    @PutMapping("/merchants/{merchantId}/applications/{appId}/collection-code/status")
    public MerchantService.CollectionCodeView setCollectionCodeStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StatusRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.setCollectionCodeStatus(subject(jwt), merchantId, appId,
                request.version(), request.enabled());
    }

    @PostMapping("/merchants/{merchantId}/applications/{appId}/collection-code/replacement")
    public MerchantService.CollectionCodeView replaceCollectionCode(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String appId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.replaceCollectionCode(
                subject(jwt), merchantId, appId, request.version());
    }

    @GetMapping("/merchants/{merchantId}/orders")
    public MerchantService.MerchantOrderPage orders(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return merchants.orders(subject(jwt), merchantId, page, size, orderNo, status, channel, from, to);
    }

    @GetMapping("/merchants/{merchantId}/orders/channel-distribution")
    public List<MerchantService.ChannelDistributionView> channelDistribution(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID merchantId) {
        return merchants.channelDistribution(subject(jwt), merchantId);
    }

    @GetMapping("/merchants/{merchantId}/orders/{paymentOrderNo}")
    public MerchantService.MerchantOrderDetailView order(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String paymentOrderNo) {
        return merchants.order(subject(jwt), merchantId, paymentOrderNo);
    }

    @PostMapping("/merchants/{merchantId}/orders/{paymentOrderNo}/refunds")
    public com.minipay.payment.domain.model.Refund refund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID merchantId,
            @PathVariable String paymentOrderNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return merchants.refundOrder(subject(jwt), merchantId, paymentOrderNo,
                idempotencyKey, request.amountCent(), request.reason());
    }

    private static UUID subject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new PaymentProblemException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED);
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) {
            throw new PaymentProblemException("INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST);
        }
    }

    private static String requestId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    public record OnboardingRequest(
            @NotNull MerchantType merchantType,
            @NotBlank @Size(min = 2, max = 64) String shopName,
            @Size(max = 16) String mccCode,
            @Size(max = 200) String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @NotBlank @Size(max = 4000) String shopImages,
            @NotBlank @Size(min = 2, max = 64) String contactName,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String contactMobile,
            @Size(max = 254) String contactEmail,
            @Size(max = 500) String remark) {
    }

    public record OnboardingResubmitRequest(
            @PositiveOrZero long version,
            @NotNull MerchantType merchantType,
            @NotBlank @Size(min = 2, max = 64) String shopName,
            @Size(max = 16) String mccCode,
            @Size(max = 200) String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @NotBlank @Size(max = 4000) String shopImages,
            @NotBlank @Size(min = 2, max = 64) String contactName,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String contactMobile,
            @Size(max = 254) String contactEmail,
            @Size(max = 500) String remark) {
    }

    public record MerchantProfileRequest(
            @PositiveOrZero long version,
            @Size(min = 2, max = 32) String shortName,
            @Size(max = 16) String mccCode,
            @Size(max = 200) String address,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Size(max = 4000) String shopImages,
            @Size(max = 64) String contactName,
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$") String contactMobile,
            @Size(max = 254) String contactEmail,
            @Size(max = 500) String remark) {
    }

    public record ApplicationApplyRequest(@NotBlank @Size(min = 2, max = 64) String name) {
    }

    public record ApplicationRequest(
            @PositiveOrZero long version,
            @NotBlank @Size(min = 2, max = 64) String name,
            @NotBlank @Size(max = 512) String notifyUrl,
            @Size(max = 512) String refundNotifyUrl,
            @Size(max = 100) List<@Size(max = 43) String> ipWhiteList,
            @NotNull @Size(min = 1, max = 8) List<@NotBlank String> permissions,
            @Size(max = 8) List<@NotBlank String> availableChannels) {
        MerchantService.ApplicationInput toInput() {
            return new MerchantService.ApplicationInput(name, notifyUrl, refundNotifyUrl,
                    ipWhiteList, permissions, availableChannels);
        }
    }

    public record VersionRequest(@PositiveOrZero long version) {
    }

    public record StatusRequest(@PositiveOrZero long version, boolean enabled) {
    }

    public record RefundRequest(@Positive long amountCent, @Size(max = 256) String reason) {
    }

    public record ApplicationCollectionCode(
            MerchantService.ApplicationView application,
            MerchantService.CollectionCodeView collectionCode) {
    }
}
