package com.minipay.managementbff.infrastructure.security;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class IdentityLogoutSuccessHandler implements ServerLogoutSuccessHandler {
    private final ServerRedirectStrategy redirects = new DefaultServerRedirectStrategy();
    private final String identityPublicUrl;

    public IdentityLogoutSuccessHandler(
            @Value("${minipay.identity-public-url}") String identityPublicUrl) {
        this.identityPublicUrl = identityPublicUrl;
    }

    @Override
    public Mono<Void> onLogoutSuccess(
            WebFilterExchange exchange,
            Authentication authentication) {
        String location = identityPublicUrl + "/session/logout?target=ops";
        return redirects.sendRedirect(exchange.getExchange(), URI.create(location));
    }
}
