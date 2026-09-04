package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.ChatMediaService;
import com.minipay.agent.application.service.VoiceMediaException;
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
@RequestMapping("/api/v1/agent/chat-media")
public class ChatMediaController {
    private final ChatMediaService service;
    public ChatMediaController(ChatMediaService service) { this.service = service; }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@Valid @RequestBody UploadRequest body, @AuthenticationPrincipal Jwt jwt) {
        try {
            var value = service.createUpload(UUID.fromString(jwt.getSubject()), body.conversationId(),
                    body.mediaKind(), body.contentType(), body.sizeBytes(), body.sha256());
            return new UploadResponse(value.mediaId(), value.uploadUrl(), value.requiredHeaders(), value.expiresAt());
        } catch (VoiceMediaException error) { throw problem(error); }
    }

    @PostMapping("/{mediaId}/complete")
    public CompleteResponse complete(@PathVariable UUID mediaId, @Valid @RequestBody CompleteRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            var value = service.complete(UUID.fromString(jwt.getSubject()), mediaId,
                    body.width(), body.height(), body.durationMs());
            return new CompleteResponse(value.id(), value.kind(), value.contentType(),
                    value.width(), value.height(), value.durationMs());
        } catch (VoiceMediaException error) { throw problem(error); }
    }

    @GetMapping("/{mediaId}/playback")
    public PlaybackResponse playback(@PathVariable UUID mediaId, @AuthenticationPrincipal Jwt jwt) {
        try {
            var value = service.playback(UUID.fromString(jwt.getSubject()), mediaId);
            return new PlaybackResponse(value.playbackUrl(), value.expiresAt());
        } catch (VoiceMediaException error) { throw problem(error); }
    }

    private static ResponseStatusException problem(VoiceMediaException error) {
        HttpStatus status = error.code().contains("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : error.code().contains("FORBIDDEN") ? HttpStatus.FORBIDDEN
                : error.code().contains("UNAVAILABLE") ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return new ResponseStatusException(status, error.code(), error);
    }

    public record UploadRequest(@NotBlank String conversationId, @NotBlank String mediaKind,
            @NotBlank String contentType, @Min(1) @Max(104857600) long sizeBytes,
            @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) {}
    public record CompleteRequest(@Min(1) @Max(8192) int width, @Min(1) @Max(8192) int height,
                                  @Min(1000) @Max(60000) Integer durationMs) {}
    public record UploadResponse(UUID mediaId, String uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}
    public record CompleteResponse(UUID mediaId, String mediaKind, String contentType,
                                   Integer width, Integer height, Integer durationMs) {}
    public record PlaybackResponse(String playbackUrl, Instant expiresAt) {}
}
