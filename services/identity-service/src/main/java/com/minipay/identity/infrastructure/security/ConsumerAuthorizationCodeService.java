package com.minipay.identity.infrastructure.security;

import com.minipay.identity.application.service.LoginRejectedException;
import com.minipay.identity.application.service.UuidV7;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

@Service
public class ConsumerAuthorizationCodeService {
    public static final String DEVICE_ID_ATTRIBUTE = "minipay.device_id";
    public static final String SESSION_ID_ATTRIBUTE = "minipay.session_id";
    private static final Set<String> CONSUMER_SCOPES = Set.of(
            "identity.profile.read",
            "identity.profile.write",
            "identity.payment-authorization.write",
            "payment.transfer.read",
            "payment.transfer.write",
            "payment.recharge.read",
            "payment.recharge.write",
            "payment.withdrawal.read",
            "payment.withdrawal.write",
            "payment.bank-card.read",
            "payment.bank-card.write",
            "payment.order.read",
            "payment.order.write",
            "payment.collection-code.read",
            "merchant.portal.read",
            "merchant.portal.write",
            "wallet.read",
            "wallet.write",
            "agent.conversation",
            "commerce.use");

    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationService authorizations;
    private final String issuer;
    private final String expectedClientId;
    private final String expectedRedirectUri;
    private final String merchantClientId;
    private final String merchantRedirectUri;
    private final Duration codeTtl;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ConsumerAuthorizationCodeService(
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            @Value("${minipay.identity.issuer}") String issuer,
            @Value("${minipay.identity.android-client.client-id}") String expectedClientId,
            @Value("${minipay.identity.android-client.redirect-uri}") String expectedRedirectUri,
            @Value("${minipay.identity.merchant-bff-client.client-id}") String merchantClientId,
            @Value("${minipay.identity.merchant-bff-client.redirect-uri}") String merchantRedirectUri,
            @Value("${minipay.identity.android-client.authorization-code-ttl:60s}") Duration codeTtl) {
        this.clients = clients;
        this.authorizations = authorizations;
        this.issuer = issuer;
        this.expectedClientId = expectedClientId;
        this.expectedRedirectUri = expectedRedirectUri;
        this.merchantClientId = merchantClientId;
        this.merchantRedirectUri = merchantRedirectUri;
        this.codeTtl = codeTtl;
    }

    ConsumerAuthorizationCodeService(
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            String issuer,
            String expectedClientId,
            String expectedRedirectUri,
            Duration codeTtl) {
        this.clients = clients;
        this.authorizations = authorizations;
        this.issuer = issuer;
        this.expectedClientId = expectedClientId;
        this.expectedRedirectUri = expectedRedirectUri;
        this.merchantClientId = expectedClientId;
        this.merchantRedirectUri = expectedRedirectUri;
        this.codeTtl = codeTtl;
    }

    public IssuedAuthorizationCode issue(
            ConsumerPrincipal consumer,
            String clientId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String deviceId) {
        RegisteredClient client = validateClient(clientId, redirectUri);
        if (!"S256".equals(codeChallengeMethod)
                || codeChallenge == null
                || !codeChallenge.matches("^[A-Za-z0-9_-]{43,128}$")) {
            throw new LoginRejectedException("PKCE_INVALID");
        }

        Set<String> scopes = client.getScopes().stream()
                .filter(CONSUMER_SCOPES::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(issuer + "/oauth2/authorize")
                .clientId(client.getClientId())
                .redirectUri(redirectUri)
                .scopes(scopes)
                .additionalParameters(Map.of(
                        "code_challenge", codeChallenge,
                        "code_challenge_method", "S256"))
                .build();
        UserDetails user = User.withUsername(consumer.userId().toString())
                .password("")
                .authorities("ROLE_CONSUMER")
                .build();
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities());

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(codeTtl);
        String rawCode = randomToken();
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(rawCode, issuedAt, expiresAt);
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UuidV7.generate().toString())
                .principalName(consumer.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .attribute(Principal.class.getName(), principal)
                .attribute(DEVICE_ID_ATTRIBUTE, deviceId)
                .attribute(SESSION_ID_ATTRIBUTE, UuidV7.generate().toString())
                .token(code)
                .build();
        authorizations.save(authorization);
        return new IssuedAuthorizationCode(rawCode, expiresAt);
    }

    private RegisteredClient validateClient(String clientId, String redirectUri) {
        boolean androidClient = expectedClientId.equals(clientId) && expectedRedirectUri.equals(redirectUri);
        boolean merchantBffClient = merchantClientId.equals(clientId) && merchantRedirectUri.equals(redirectUri);
        if (!androidClient && !merchantBffClient) {
            throw new LoginRejectedException("OAUTH_CLIENT_INVALID");
        }
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null
                || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE)
                || !client.getRedirectUris().contains(redirectUri)) {
            throw new LoginRejectedException("OAUTH_CLIENT_INVALID");
        }
        return client;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedAuthorizationCode(String authorizationCode, Instant expiresAt) {
    }
}
