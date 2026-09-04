package com.minipay.managementbff.interfaces.rest;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
public class SessionController {

    @GetMapping({"/session", "/ops-session"})
    public Mono<SessionResponse> session(Mono<Principal> principal) {
        return principal
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .map(Authentication::getPrincipal)
                .filter(OidcUser.class::isInstance)
                .cast(OidcUser.class)
                .map(user -> new SessionResponse(
                        true,
                        "/oauth2/authorization/minipay-ops",
                        new AuthenticatedAdmin(
                                user.getClaimAsString("user_id"),
                                user.getClaimAsString("display_name"),
                                user.getClaimAsStringList("roles"),
                                user.getAuthorities().stream()
                                        .map(authority -> authority.getAuthority())
                                        .filter(authority -> authority.startsWith("SCOPE_"))
                                        .map(authority -> authority.substring("SCOPE_".length()))
                                        .sorted()
                                        .collect(Collectors.toList()))))
                .defaultIfEmpty(new SessionResponse(
                        false,
                        "/oauth2/authorization/minipay-ops",
                        null));
    }

    @GetMapping({"/csrf", "/ops-csrf"})
    public Mono<ResponseEntity<Map<String, String>>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> csrfToken = exchange.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null) {
            return Mono.error(new IllegalStateException("CSRF token is unavailable"));
        }
        return csrfToken.map(value -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "headerName", value.getHeaderName(),
                        "parameterName", value.getParameterName(),
                        "token", value.getToken())));
    }

    public record SessionResponse(boolean authenticated, String loginUrl, AuthenticatedAdmin admin) {
    }

    public record AuthenticatedAdmin(
            String userId,
            String displayName,
            List<String> roles,
            List<String> permissions) {
    }
}
