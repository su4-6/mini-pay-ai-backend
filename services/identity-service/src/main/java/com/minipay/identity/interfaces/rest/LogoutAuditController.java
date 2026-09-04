package com.minipay.identity.interfaces.rest;

import com.minipay.identity.infrastructure.persistence.LoginAuditRepository;
import com.minipay.identity.infrastructure.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/logout-audits")
public class LogoutAuditController {
    private final LoginAuditRepository audits;

    public LogoutAuditController(LoginAuditRepository audits) {
        this.audits = audits;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void append(JwtAuthenticationToken authentication, HttpServletRequest request) {
        UUID userId = UUID.fromString(authentication.getToken().getClaimAsString("user_id"));
        String requestId = RequestIdFilter.get(request);
        String clientAddress =
                request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        audits.appendLogout(
                userId,
                userId.toString(),
                clientAddress,
                userAgent,
                requestId);
    }
}
