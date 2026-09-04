package com.minipay.identity.interfaces.rest;

import com.minipay.identity.application.service.TransferRecipientLookupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfer-recipients")
public class TransferRecipientController {
    private final TransferRecipientLookupService recipients;

    public TransferRecipientController(TransferRecipientLookupService recipients) {
        this.recipients = recipients;
    }

    @PostMapping("/resolve")
    public TransferRecipientLookupService.RecipientView resolve(
            JwtAuthenticationToken authentication,
            @RequestBody ResolveTransferRecipientRequest request,
            HttpServletRequest servletRequest) {
        return recipients.resolveMobile(
                UUID.fromString(authentication.getToken().getClaimAsString("user_id")),
                request.mobile(), servletRequest.getRemoteAddr());
    }

    public record ResolveTransferRecipientRequest(String mobile) {
    }
}
