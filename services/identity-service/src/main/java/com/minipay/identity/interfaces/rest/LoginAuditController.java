package com.minipay.identity.interfaces.rest;

import com.minipay.identity.infrastructure.persistence.LoginAuditRepository;
import com.minipay.identity.infrastructure.persistence.LoginAuditRepository.LoginAuditItem;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/login-audits")
public class LoginAuditController {
    private final LoginAuditRepository audits;

    public LoginAuditController(LoginAuditRepository audits) {
        this.audits = audits;
    }

    @GetMapping
    public LoginAuditPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return new LoginAuditPage(audits.findPage(safePage, safeSize), safePage, safeSize, audits.count());
    }

    public record LoginAuditPage(List<LoginAuditItem> items, int page, int size, long total) {
    }
}
