package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ConsumerProfileService;
import com.minipay.identity.application.service.ConsumerProfileService.ProfileView;
import com.minipay.identity.application.service.ConsumerProfileService.UploadGrant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class ConsumerProfileController {
    private final ConsumerProfileService profiles;

    public ConsumerProfileController(ConsumerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public ProfileView get(JwtAuthenticationToken authentication) {
        return profiles.get(userId(authentication));
    }

    @PostMapping("/avatar-uploads")
    public ResponseEntity<UploadGrant> createUpload(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody AvatarUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profiles.createUpload(
                userId(authentication), request.contentType(), request.sizeBytes(), request.sha256()));
    }

    @PatchMapping
    public ProfileView update(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.update(
                userId(authentication), request.nickname(), request.avatarUploadId(), request.version());
    }

    private UUID userId(JwtAuthenticationToken authentication) {
        return UUID.fromString(authentication.getToken().getClaimAsString("user_id"));
    }

    public record AvatarUploadRequest(
            @NotBlank String contentType,
            @Min(1) @Max(5_242_880) long sizeBytes,
            @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) {
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(min = 2, max = 20) String nickname,
            UUID avatarUploadId,
            @Min(0) long version) {
    }
}
