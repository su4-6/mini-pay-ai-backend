package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ApplicationAuthorizationService;
import com.minipay.identity.application.service.ApplicationAuthorizationService.AuthorizationView;
import com.minipay.identity.application.service.ApplicationAuthorizationService.DisclosureView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class ApplicationAuthorizationController {
    private final ApplicationAuthorizationService authorizations;

    public ApplicationAuthorizationController(ApplicationAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @GetMapping("/api/v1/users/me/application-authorizations")
    public List<AuthorizationView> list(JwtAuthenticationToken authentication) {
        return authorizations.list(userId(authentication));
    }

    @GetMapping("/api/v1/users/me/application-authorizations/{applicationId}")
    public AuthorizationView get(
            JwtAuthenticationToken authentication,
            @PathVariable @NotBlank @Size(max = 64) String applicationId) {
        return authorizations.get(userId(authentication), applicationId);
    }

    @PostMapping("/api/v1/users/me/application-authorizations/{applicationId}")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorizationView grant(
            JwtAuthenticationToken authentication,
            @PathVariable @NotBlank @Size(max = 64) String applicationId,
            @Valid @RequestBody GrantRequest request,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        return authorizations.grant(userId(authentication), applicationId,
                request.scopes(), request.consentVersion(), request.phoneChallengeId(),
                request.verificationCode(), idempotencyKey);
    }

    @PostMapping("/api/v1/users/me/application-authorizations/{applicationId}/phone-challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplicationAuthorizationService.PhoneChallengeView createPhoneChallenge(
            JwtAuthenticationToken authentication,
            @PathVariable @NotBlank @Size(max = 64) String applicationId,
            @Valid @RequestBody PhoneChallengeRequest request,
            HttpServletRequest servletRequest) {
        return authorizations.createPhoneChallenge(
                userId(authentication), applicationId, request.mobile(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/api/v1/users/me/application-authorizations/{applicationId}/scope-grants")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorizationView grantScopes(
            JwtAuthenticationToken authentication,
            @PathVariable @NotBlank @Size(max = 64) String applicationId,
            @Valid @RequestBody GrantRequest request,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        return authorizations.grantScopes(userId(authentication), applicationId,
                request.scopes(), request.consentVersion(), idempotencyKey);
    }

    @DeleteMapping("/api/v1/users/me/application-authorizations/{applicationId}")
    public ResponseEntity<Void> revoke(
            JwtAuthenticationToken authentication,
            @PathVariable @NotBlank @Size(max = 64) String applicationId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
        authorizations.revoke(userId(authentication), applicationId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/internal/v1/users/{userId}/application-disclosures/{applicationId}")
    public DisclosureView disclosure(
            @PathVariable UUID userId,
            @PathVariable @NotBlank @Size(max = 64) String applicationId) {
        return authorizations.disclosure(userId, applicationId);
    }

    private UUID userId(JwtAuthenticationToken authentication) {
        String claim = authentication.getToken().getClaimAsString("user_id");
        return UUID.fromString(claim == null ? authentication.getToken().getSubject() : claim);
    }

    public record GrantRequest(
            @NotEmpty Set<@NotBlank @Size(max = 64) String> scopes,
            @Min(1) @Max(10000) int consentVersion,
            String phoneChallengeId,
            String verificationCode) { }

    public record PhoneChallengeRequest(@NotBlank @Size(max = 32) String mobile) { }
}
