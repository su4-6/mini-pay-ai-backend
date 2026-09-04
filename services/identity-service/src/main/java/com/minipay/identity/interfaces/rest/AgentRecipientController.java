package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.TransferRecipientLookupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/agent/recipients")
public class AgentRecipientController {
    private final TransferRecipientLookupService recipients;

    public AgentRecipientController(TransferRecipientLookupService recipients) {
        this.recipients = recipients;
    }

    @PostMapping("/resolve-exact-mobile")
    public TransferRecipientLookupService.RecipientView resolve(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResolveExactMobileRequest request) {
        return recipients.resolveMobile(
                UUID.fromString(jwt.getClaimAsString("user_id")),
                request.mobile(),
                "agent-device:" + jwt.getClaimAsString("device_id"));
    }

    public record ResolveExactMobileRequest(@NotBlank @Size(max = 20) String mobile) {
    }
}
