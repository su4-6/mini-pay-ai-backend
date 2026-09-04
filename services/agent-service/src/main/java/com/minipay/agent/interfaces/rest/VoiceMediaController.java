package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.VoiceMediaException;
import com.minipay.agent.application.service.VoiceMediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/agent/voice-media")
public class VoiceMediaController {
    private final VoiceMediaService service;
    public VoiceMediaController(VoiceMediaService service) { this.service = service; }

    @PostMapping("/uploads") @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@Valid @RequestBody UploadRequest body, @AuthenticationPrincipal Jwt jwt) {
        try {
            var value = service.createUpload(UUID.fromString(jwt.getSubject()), body.conversationId(), body.contentType(), body.sizeBytes(), body.sha256());
            return new UploadResponse(value.mediaId(), value.uploadUrl(), value.requiredHeaders(), value.expiresAt());
        } catch (VoiceMediaException e) { throw problem(e); }
    }

    @PostMapping("/{mediaId}/complete")
    public CompleteResponse complete(@PathVariable UUID mediaId, @Valid @RequestBody CompleteRequest body, @AuthenticationPrincipal Jwt jwt) {
        try {
            var value = service.complete(UUID.fromString(jwt.getSubject()), mediaId, body.durationMs());
            return new CompleteResponse(value.id(), value.durationMs());
        } catch (VoiceMediaException e) { throw problem(e); }
    }

    @GetMapping("/{mediaId}/playback")
    public PlaybackResponse playback(@PathVariable UUID mediaId, @AuthenticationPrincipal Jwt jwt) {
        try {
            var value = service.playback(UUID.fromString(jwt.getSubject()), mediaId);
            return new PlaybackResponse(value.playbackUrl(), value.expiresAt());
        } catch (VoiceMediaException e) { throw problem(e); }
    }

    private static ResponseStatusException problem(VoiceMediaException e) {
        HttpStatus status = e.code().contains("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : e.code().contains("FORBIDDEN") ? HttpStatus.FORBIDDEN
                : e.code().contains("UNAVAILABLE") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
        return new ResponseStatusException(status, e.code(), e);
    }

    public record UploadRequest(@NotBlank String conversationId, @NotBlank String contentType,
            @Min(1) @Max(1048576) long sizeBytes, @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) {}
    public record CompleteRequest(@Min(1000) @Max(60000) int durationMs) {}
    public record UploadResponse(UUID mediaId, String uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}
    public record CompleteResponse(UUID mediaId, Integer durationMs) {}
    public record PlaybackResponse(String playbackUrl, Instant expiresAt) {}
}
