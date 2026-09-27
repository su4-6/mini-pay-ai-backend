package com.minipay.consumerbff.infrastructure.security;

import com.minipay.consumerbff.infrastructure.session.ConsumerSessionAttributes;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Turns a WebSession that already completed the PKCE login into an authentication. Tokens stay in
 * the session; only the consumer id becomes the principal, so nothing credential-bearing enters the
 * security context.
 */
public class ConsumerSessionAuthenticationConverter implements ServerAuthenticationConverter {

    private static final List<SimpleGrantedAuthority> CONSUMER_ROLE =
            List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"));

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return exchange.getSession().handle((session, sink) -> {
            if (!isAuthenticated(session)) {
                return;
            }
            sink.next(UsernamePasswordAuthenticationToken.authenticated(
                    session.getAttribute(ConsumerSessionAttributes.CONSUMER_ID), null,
                    CONSUMER_ROLE));
        });
    }

    static boolean isAuthenticated(WebSession session) {
        String userId = session.getAttribute(ConsumerSessionAttributes.CONSUMER_ID);
        if (userId == null) {
            return false;
        }
        try {
            UUID.fromString(userId);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
