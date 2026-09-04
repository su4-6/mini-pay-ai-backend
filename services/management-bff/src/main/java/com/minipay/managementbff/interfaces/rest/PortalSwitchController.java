package com.minipay.managementbff.interfaces.rest;

import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Starts a portal switch with a fresh BFF and Identity browser session. */
@RestController
public class PortalSwitchController {
    private static final Set<String> TARGETS = Set.of("ops", "merchant", "admin");
    private final String identityPublicUrl;

    public PortalSwitchController(@Value("${minipay.identity-public-url}") String identityPublicUrl) {
        this.identityPublicUrl = identityPublicUrl;
    }

    @GetMapping("/switch-login")
    public Mono<Void> switchLogin(@RequestParam(defaultValue = "ops") String target, ServerWebExchange exchange) {
        String safeTarget = TARGETS.contains(target) ? target : "ops";
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create(identityPublicUrl + "/session/logout?target=" + safeTarget));
        return exchange.getSession().flatMap(session -> session.invalidate());
    }
}
