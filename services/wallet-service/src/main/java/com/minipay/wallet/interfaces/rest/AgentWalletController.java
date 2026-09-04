package com.minipay.wallet.interfaces.rest;

import com.minipay.wallet.application.service.WalletApplicationService;
import com.minipay.wallet.application.service.WalletApplicationService.BillAggregation;
import com.minipay.wallet.domain.model.BillPage;
import com.minipay.wallet.domain.model.BillQuery;
import com.minipay.wallet.domain.model.WalletSummary;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/agent")
public class AgentWalletController {
    private final WalletApplicationService wallets;

    public AgentWalletController(WalletApplicationService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/wallet-summary")
    public WalletSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return wallets.getWallet(userId(jwt));
    }

    @GetMapping("/wallet-bills")
    public BillPage bills(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return wallets.listBills(userId(jwt),
                new BillQuery(from, to, direction, businessType, source, status, page, size));
    }

    @GetMapping("/wallet-bill-aggregations")
    public BillAggregation aggregate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status) {
        return wallets.aggregateBills(userId(jwt),
                new BillQuery(from, to, direction, businessType, source, status, 1, 1));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("user_id"));
    }
}
