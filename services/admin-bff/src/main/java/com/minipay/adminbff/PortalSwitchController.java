package com.minipay.adminbff;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Prevents a portal shortcut from reusing an existing administrator session. */
@RestController
public class PortalSwitchController {
    private final String identityPublicUrl;
    private final String adminWebUrl;

    public PortalSwitchController(
            @Value("${minipay.identity-public-url}") String identityPublicUrl,
            @Value("${minipay.admin-web-url}") String adminWebUrl) {
        this.identityPublicUrl = identityPublicUrl;
        this.adminWebUrl = adminWebUrl;
    }

    /** Keep old bookmarks to the BFF login address working after splitting the UI onto port 8002. */
    @GetMapping("/login")
    public Mono<Void> login(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        String baseUrl = adminWebUrl.endsWith("/") ? adminWebUrl : adminWebUrl + "/";
        exchange.getResponse().getHeaders().setLocation(URI.create(baseUrl + "login"));
        return exchange.getResponse().setComplete();
    }

    @GetMapping("/switch-login")
    public Mono<Void> switchLogin(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create(identityPublicUrl + "/session/logout?target=admin"));
        return exchange.getSession().flatMap(session -> session.invalidate());
    }
}
