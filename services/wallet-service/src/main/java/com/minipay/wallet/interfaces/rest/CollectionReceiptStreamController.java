package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletProblemException;
import com.minipay.wallet.infrastructure.realtime.CollectionReceiptHub;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/wallets/me/collection-receipts")
public class CollectionReceiptStreamController {
    private final CollectionReceiptHub receipts;

    public CollectionReceiptStreamController(CollectionReceiptHub receipts) {
        this.receipts = receipts;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(JwtAuthenticationToken authentication) {
        return receipts.subscribe(currentUser(authentication));
    }

    private UUID currentUser(JwtAuthenticationToken authentication) {
        if (!Boolean.TRUE.equals(authentication.getToken().getClaim("onboarding_completed"))
                || !Boolean.TRUE.equals(authentication.getToken().getClaim("real_name_verified"))) {
            throw new WalletProblemException("REAL_NAME_VERIFICATION_REQUIRED", HttpStatus.FORBIDDEN);
        }
        String userId = authentication.getToken().getClaimAsString("user_id");
        if (userId == null) throw new WalletProblemException("INVALID_SUBJECT", HttpStatus.FORBIDDEN);
        return UUID.fromString(userId);
    }
}
