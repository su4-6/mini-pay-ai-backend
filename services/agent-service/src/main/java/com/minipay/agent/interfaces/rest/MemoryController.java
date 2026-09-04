package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.MemoryApplicationService;
import com.minipay.agent.domain.model.ai.MemoryItem;
import com.minipay.agent.domain.model.ai.MemorySetting;
import com.minipay.agent.domain.model.ai.MemoryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@RestController
@RequestMapping("/api/v1/agent/ai/memory")
public class MemoryController {
    private final MemoryApplicationService memory;

    public MemoryController(MemoryApplicationService memory) {
        this.memory = memory;
    }

    @GetMapping("/settings")
    public MemorySetting settings(@AuthenticationPrincipal Jwt jwt) {
        return memory.setting(userId(jwt));
    }

    @PutMapping("/settings")
    public MemorySetting updateSettings(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody UpdateSettingsRequest request) {
        return memory.updateSetting(userId(jwt), request.enabled(), request.foodPreferenceEnabled(),
                request.allergenAvoidanceEnabled(), request.mealBudgetEnabled(),
                request.contactAliasEnabled(), request.addressAliasEnabled(), request.version());
    }

    @GetMapping("/items")
    public List<MemoryItemResponse> list(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam(required = false) MemoryType type,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return memory.list(userId(jwt), type, limit).stream().map(MemoryItemResponse::from).toList();
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryItemResponse create(@AuthenticationPrincipal Jwt jwt,
                             @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
                             @Valid @RequestBody SaveMemoryRequest request) {
        return MemoryItemResponse.from(memory.create(userId(jwt), request.type(), request.displayValue(),
                request.referenceType(), request.referenceId(), request.consentMessageId(), idempotencyKey));
    }

    @PatchMapping("/items/{itemId}")
    public MemoryItemResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId,
                             @Valid @RequestBody UpdateMemoryRequest request) {
        return MemoryItemResponse.from(memory.update(userId(jwt), itemId, request.displayValue(),
                request.referenceType(), request.referenceId(), request.consentMessageId(), request.version()));
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId,
                       @RequestParam @Min(0) long version) {
        memory.delete(userId(jwt), itemId, version);
    }

    private static UUID userId(Jwt jwt) {
        String claim = jwt.getClaimAsString("user_id");
        return UUID.fromString(claim == null ? jwt.getSubject() : claim);
    }

    public record UpdateSettingsRequest(
            boolean enabled,
            boolean foodPreferenceEnabled,
            boolean allergenAvoidanceEnabled,
            boolean mealBudgetEnabled,
            boolean contactAliasEnabled,
            boolean addressAliasEnabled,
            @Min(0) long version) {
    }

    public record SaveMemoryRequest(
            @NotNull MemoryType type,
            @NotBlank @Size(max = 256) String displayValue,
            @Size(max = 32) String referenceType,
            @Size(max = 128) String referenceId,
            UUID consentMessageId) {
    }

    public record UpdateMemoryRequest(
            @NotBlank @Size(max = 256) String displayValue,
            @Size(max = 32) String referenceType,
            @Size(max = 128) String referenceId,
            UUID consentMessageId,
            @Min(0) long version) {
    }

    public record MemoryItemResponse(
            UUID id,
            UUID userId,
            MemoryType type,
            String displayValue,
            String referenceType,
            String referenceId,
            String status,
            UUID consentMessageId,
            String consentSource,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
        static MemoryItemResponse from(MemoryItem item) {
            return new MemoryItemResponse(item.id(), item.userId(), item.type(), item.displayValue(),
                    item.referenceType(), item.referenceId(), item.status(), item.consentMessageId(),
                    item.consentSource(), item.version(), item.createdAt(), item.updatedAt());
        }
    }
}
