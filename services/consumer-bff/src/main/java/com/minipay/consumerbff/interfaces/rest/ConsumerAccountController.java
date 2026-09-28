package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.error.UpstreamProblemException;
import com.minipay.consumerbff.application.service.ConsumerSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/** Same-origin profile, onboarding and account-security surface for the Consumer H5. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ConsumerAccountController {

    private static final long MAX_REAL_NAME_REQUEST_BYTES = 1_500_000;

    private final ConsumerSessionService sessions;

    public ConsumerAccountController(ConsumerSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/users/me")
    public Mono<ResponseEntity<String>> profile(
            WebSession session, ServerWebExchange exchange) {
        return proxy(session, exchange, HttpMethod.GET, "/api/v1/users/me", Map.of(), null, null);
    }

    @PatchMapping("/users/me")
    public Mono<ResponseEntity<String>> updateProfile(
            WebSession session,
            ServerWebExchange exchange,
            @Valid @RequestBody UpdateProfileRequest request) {
        return sessions.updateProfile(session, exchange, request.nickname(), request.version())
                .map(UpstreamResponses::toEntity);
    }

    @PutMapping("/users/me/onboarding")
    public Mono<ResponseEntity<String>> completeOnboarding(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody OnboardingRequest request) {
        return sessions.completeOnboarding(
                        session, exchange, request.nickname(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    @GetMapping("/users/me/capabilities")
    public Mono<ResponseEntity<String>> capabilities(
            WebSession session, ServerWebExchange exchange) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/users/me/capabilities", Map.of(), null, null);
    }

    @PostMapping(value = "/real-name-verifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<String>> verifyRealName(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        long length = exchange.getRequest().getHeaders().getContentLength();
        if (contentType == null || contentType.getParameter("boundary") == null) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "MULTIPART_BOUNDARY_REQUIRED", "实名认证请求格式不正确"));
        }
        if (length > MAX_REAL_NAME_REQUEST_BYTES) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "FACE_IMAGE_TOO_LARGE", "人脸照片不能超过 1 MB"));
        }
        return sessions.submitRealName(session, exchange, contentType,
                        limitMultipart(exchange.getRequest().getBody()), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    private static Flux<DataBuffer> limitMultipart(Flux<DataBuffer> body) {
        AtomicLong received = new AtomicLong();
        return body.handle((buffer, sink) -> {
            if (received.addAndGet(buffer.readableByteCount()) > MAX_REAL_NAME_REQUEST_BYTES) {
                DataBufferUtils.release(buffer);
                sink.error(new UpstreamProblemException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "FACE_IMAGE_TOO_LARGE",
                        "人脸照片不能超过 1 MB"));
                return;
            }
            sink.next(buffer);
        });
    }

    @GetMapping("/real-name-verifications/{verificationId}")
    public Mono<ResponseEntity<String>> realNameResult(
            WebSession session, ServerWebExchange exchange, @PathVariable String verificationId) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/real-name-verifications/" + verificationId, Map.of(), null, null);
    }

    @GetMapping("/users/me/account-security")
    public Mono<ResponseEntity<String>> accountSecurity(
            WebSession session, ServerWebExchange exchange) {
        return proxy(session, exchange, HttpMethod.GET,
                "/api/v1/users/me/account-security", Map.of(), null, null);
    }

    @PostMapping("/users/me/phone-change-challenges")
    public Mono<ResponseEntity<String>> requestPhoneChange(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody MobileRequest request) {
        return proxy(session, exchange, HttpMethod.POST,
                "/api/v1/users/me/phone-change-challenges", Map.of(),
                Map.of("mobile", request.mobile()), idempotencyKey);
    }

    @PutMapping("/users/me/phone")
    public Mono<ResponseEntity<String>> confirmPhoneChange(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody ConfirmPhoneRequest request) {
        return sessions.confirmPhoneChange(session, exchange, request.mobile(),
                        request.challengeId(), request.code(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/users/me/payment-password-change-challenges")
    public Mono<ResponseEntity<String>> requestPaymentPasswordChange(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody MobileRequest request) {
        return sessions.requestPaymentPasswordChange(
                        session, exchange, request.mobile(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/users/me/payment-password-change-challenges/{challengeId}/verifications")
    public Mono<ResponseEntity<String>> verifyPaymentPasswordChange(
            WebSession session,
            ServerWebExchange exchange,
            @PathVariable @NotBlank String challengeId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody CodeRequest request) {
        return sessions.verifyPaymentPasswordChange(
                        session, exchange, challengeId, request.code(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    @PostMapping("/users/me/payment-password-changes")
    public Mono<ResponseEntity<String>> changePaymentPassword(
            WebSession session,
            ServerWebExchange exchange,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
            @Valid @RequestBody ChangePaymentPasswordRequest request) {
        return sessions.changePaymentPassword(session, exchange, request.verificationToken(),
                        request.newPassword(), idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    private Mono<ResponseEntity<String>> proxy(
            WebSession session,
            ServerWebExchange exchange,
            HttpMethod method,
            String path,
            Map<String, String> query,
            Object body,
            String idempotencyKey) {
        return sessions.proxy(session, exchange, method, path, query, body, idempotencyKey)
                .map(UpstreamResponses::toEntity);
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(min = 2, max = 20) String nickname, @Min(0) long version) {
    }

    public record OnboardingRequest(@NotBlank @Size(min = 2, max = 20) String nickname) {
    }

    public record MobileRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile) {
    }

    public record ConfirmPhoneRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$") String mobile,
            @NotBlank String challengeId,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record CodeRequest(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record ChangePaymentPasswordRequest(
            @NotBlank String verificationToken,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String newPassword) {
    }
}
