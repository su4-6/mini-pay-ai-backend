package com.minipay.adminbff;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class IdentityLogoutSuccessHandler implements ServerLogoutSuccessHandler {
    private final DefaultServerRedirectStrategy redirects = new DefaultServerRedirectStrategy();
    private final URI identityLogout;

    public IdentityLogoutSuccessHandler(
            @Value("${minipay.identity-public-url}") String identityPublicUrl) {
        this.identityLogout = URI.create(identityPublicUrl + "/session/logout?target=admin");
    }

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange exchange, Authentication authentication) {
        return redirects.sendRedirect(exchange.getExchange(), identityLogout);
    }
}
