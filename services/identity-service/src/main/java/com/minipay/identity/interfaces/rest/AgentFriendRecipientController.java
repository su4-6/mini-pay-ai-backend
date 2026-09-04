package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.ExactFriendRecipientLookupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/agent/contacts")
public class AgentFriendRecipientController {
    private final ExactFriendRecipientLookupService recipients;

    public AgentFriendRecipientController(ExactFriendRecipientLookupService recipients) {
        this.recipients = recipients;
    }

    @PostMapping("/resolve-exact")
    public List<ExactFriendRecipientLookupService.FriendRecipientView> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResolveExactFriendRequest request) {
        return recipients.resolve(
                UUID.fromString(jwt.getClaimAsString("user_id")),
                request.query(),
                "agent-device:" + jwt.getClaimAsString("device_id"));
    }

    public record ResolveExactFriendRequest(@NotBlank @Size(max = 64) String query) {
    }
}
