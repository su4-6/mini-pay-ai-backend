package com.minipay.identity.infrastructure.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Applies MiniPay's delegation policy before Spring Authorization Server performs the
 * RFC 8693 exchange. Returning {@code null} after validation lets the built-in provider
 * issue the token; invalid requests stop at this provider.
 */
final class DelegationTokenExchangeValidator implements AuthenticationProvider {
    static final String PURPOSE_PARAMETER = "purpose";
    static final String RUN_ID_PARAMETER = "run_id";

    private static final Map<String, Set<String>> AUDIENCE_SCOPES = Map.of(
            "agent-internal", Set.of("agent.contact.read"),
            "identity-internal", Set.of("agent.contact.read", "identity.agent.recipient.resolve"),
            "wallet-internal", Set.of(
                    "wallet.agent.summary",
                    "wallet.agent.bills.read",
                    "wallet.agent.bills.aggregate"),
            "payment-internal", Set.of(
                    "payment.agent.transfer.prepare",
                    "payment.agent.transfer.read"),
            "commerce-internal", Set.of(
                    "commerce.agent.catalog.read",
                    "commerce.agent.cart.write",
                    "commerce.agent.checkout.prepare",
                    "commerce.agent.order.read",
                    "commerce.agent.cancel.prepare"));

    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final String delegationClientId;
    private final String subjectClientId;

    DelegationTokenExchangeValidator(
            OAuth2AuthorizationService authorizations,
            RegisteredClientRepository clients,
            String delegationClientId,
            String subjectClientId) {
        this.authorizations = authorizations;
        this.clients = clients;
        this.delegationClientId = delegationClientId;
        this.subjectClientId = subjectClientId;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2TokenExchangeAuthenticationToken exchange =
                (OAuth2TokenExchangeAuthenticationToken) authentication;
        RegisteredClient actor = authenticatedClient(exchange);
        if (!delegationClientId.equals(actor.getClientId())) {
            reject(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, "Only the Agent delegation client may exchange tokens");
        }
        if (exchange.getActorToken() != null || !exchange.getResources().isEmpty()) {
            reject(OAuth2ErrorCodes.INVALID_REQUEST, "actor_token and resource are not accepted");
        }
        if (exchange.getAudiences().size() != 1) {
            reject("invalid_target", "Exactly one internal audience is required");
        }
        String audience = exchange.getAudiences().iterator().next();
        Set<String> allowedScopes = AUDIENCE_SCOPES.get(audience);
        if (allowedScopes == null || exchange.getScopes().isEmpty()
                || !allowedScopes.containsAll(exchange.getScopes())) {
            reject(OAuth2ErrorCodes.INVALID_SCOPE, "Requested scope is not allowed for the audience");
        }

        Object purpose = exchange.getAdditionalParameters().get(PURPOSE_PARAMETER);
        if (!(purpose instanceof String purposeValue)
                || !purposeValue.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            reject(OAuth2ErrorCodes.INVALID_REQUEST, "A valid purpose is required");
        }
        Object runId = exchange.getAdditionalParameters().get(RUN_ID_PARAMETER);
        if (!(runId instanceof String runIdValue) || !isUuid(runIdValue)) {
            reject(OAuth2ErrorCodes.INVALID_REQUEST, "A valid run_id is required");
        }

        OAuth2Authorization subject = authorizations.findByToken(
                exchange.getSubjectToken(), OAuth2TokenType.ACCESS_TOKEN);
        if (subject == null || subject.getAccessToken() == null || !subject.getAccessToken().isActive()) {
            reject(OAuth2ErrorCodes.INVALID_GRANT, "The subject token is inactive");
        }
        RegisteredClient subjectClient = clients.findById(subject.getRegisteredClientId());
        if (subjectClient == null || !subjectClientId.equals(subjectClient.getClientId())
                || !subject.getAuthorizedScopes().contains("agent.conversation")) {
            reject(OAuth2ErrorCodes.INVALID_GRANT, "The subject token is not an Android Agent session");
        }
        Map<String, Object> claims = subject.getAccessToken().getClaims();
        if (claims == null || !containsAudience(claims.get("aud"), "consumer-api")
                || !(claims.get("device_id") instanceof String deviceId) || deviceId.isBlank()) {
            reject(OAuth2ErrorCodes.INVALID_GRANT, "The subject token lacks device binding");
        }
        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenExchangeAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static RegisteredClient authenticatedClient(OAuth2TokenExchangeAuthenticationToken exchange) {
        if (!(exchange.getPrincipal() instanceof OAuth2ClientAuthenticationToken)) {
            reject(OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication is required");
        }
        OAuth2ClientAuthenticationToken client =
                (OAuth2ClientAuthenticationToken) exchange.getPrincipal();
        if (!client.isAuthenticated() || client.getRegisteredClient() == null) {
            reject(OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication is required");
        }
        return client.getRegisteredClient();
    }

    private static boolean containsAudience(Object claim, String expected) {
        if (claim instanceof String value) {
            return expected.equals(value);
        }
        return claim instanceof Iterable<?> values
                && java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch(expected::equals);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void reject(String code, String description) {
        throw new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}
