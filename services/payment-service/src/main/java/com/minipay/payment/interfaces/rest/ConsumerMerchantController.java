package com.minipay.payment.interfaces.rest;

import com.minipay.payment.application.port.MerchantApplyStore.ApplyPage;
import com.minipay.payment.application.port.MerchantApplyStore.ApplyView;
import com.minipay.payment.application.service.ImageStorageService;
import com.minipay.payment.application.service.MerchantApplyService;
import com.minipay.payment.application.service.MerchantInitializationService;
import com.minipay.payment.application.service.MerchantService;
import com.minipay.payment.application.service.PaymentProblemException;
import com.minipay.payment.domain.model.MerchantType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer H5 view of the same merchant aggregate used by merchant-web.
 *
 * <p>No merchant state is copied into Identity or the BFF. Both portals call the same application
 * service with the authenticated user id as owner, so an application created in either portal is
 * immediately visible in the other one.
 */
@RestController
@RequestMapping("/api/v1/consumer-merchant")
public class ConsumerMerchantController {
    private final MerchantService merchants;
    private final MerchantApplyService onboardings;
    private final MerchantInitializationService initialization;
    private final ImageStorageService images;

    public ConsumerMerchantController(
            MerchantService merchants,
            MerchantApplyService onboardings,
            MerchantInitializationService initialization,
            ImageStorageService images) {
        this.merchants = merchants;
        this.onboardings = onboardings;
        this.initialization = initialization;
        this.images = images;
    }

    @GetMapping("/merchants")
    public List<MerchantService.MerchantView> merchants(@AuthenticationPrincipal Jwt jwt) {
        return merchants.merchants(subject(jwt));
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
    public ApplyView submit(
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

    @PutMapping("/onboardings/{applyId}")
    public ApplyView resubmit(
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

    @GetMapping("/collection-code")
    public BusinessCollectionCode collectionCode(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID merchantId) {
        UUID ownerId = subject(jwt);
        MerchantService.MerchantView merchant = merchants.merchant(ownerId, merchantId);
        return new BusinessCollectionCode(
                merchant,
                merchants.currentBusinessCollectionCode(ownerId, merchantId));
    }

    @PostMapping("/image-uploads")
    public ImageStorageService.UploadGrant createImageUpload(
            @Valid @RequestBody OpsController.ImageUploadRequest request) {
        return images.createUpload(request.fileName(), request.contentType(),
                request.sizeBytes(), request.sha256());
    }

    private static UUID subject(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new PaymentProblemException("INVALID_TOKEN_SUBJECT", HttpStatus.UNAUTHORIZED);
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new PaymentProblemException("INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST);
        }
    }

    private static String requestId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public record BusinessCollectionCode(
            MerchantService.MerchantView merchant,
            MerchantService.CollectionCodeView collectionCode) {
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
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$") String contactMobile,
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
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$") String contactMobile,
            @Size(max = 254) String contactEmail,
            @Size(max = 500) String remark) {
    }
}
