package com.minipay.identity.infrastructure.security;

import com.minipay.identity.domain.model.AdminPrincipal;
import com.minipay.identity.domain.model.ConsumerPrincipal;
import com.minipay.identity.infrastructure.persistence.AdminAccountRepository;
import com.minipay.identity.infrastructure.persistence.ConsumerAccountRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.http.MediaType;

@Configuration
public class SecurityConfiguration {
    private static final Set<String> RESOURCE_API_AUDIENCES = Set.of(
            "management-api", "admin-api", "consumer-api", "merchant-api", "identity-internal");

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurity(
            HttpSecurity http,
            RequestCache requestCache,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            @Value("${minipay.identity.internal-clients.agent-delegation.client-id}") String agentDelegationClientId,
            @Value("${minipay.identity.android-client.client-id}") String androidClientId) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        .tokenEndpoint(tokenEndpoint -> tokenEndpoint
                                .authenticationProviders(providers -> providers.add(0,
                                        new DelegationTokenExchangeValidator(
                                                authorizations,
                                                clients,
                                                agentDelegationClientId,
                                                androidClientId))))
                        .clientAuthentication(clientAuthentication -> clientAuthentication
                                .authenticationConverters(converters -> converters.add(
                                        0, new PublicRefreshClientAuthenticationConverter()))
                                .authenticationProviders(providers -> providers.add(
                                        0, new PublicRefreshClientAuthenticationProvider(clients))))
                        .oidc(Customizer.withDefaults()))
                .requestCache(cache -> cache.requestCache(requestCache))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    RequestCache requestCache() {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setMatchingRequestParameterName(null);
        requestCache.setRequestMatcher(new AntPathRequestMatcher("/oauth2/authorize"));
        return requestCache;
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            JWKSource<SecurityContext> jwkSource,
            ApiSecurityProblemHandler problems,
            @Value("${minipay.identity.issuer}") String issuer) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/api/v1/auth/consumer/**",
                                "/api/v1/auth/merchant/**",
                                "/api/v1/auth/captchas/**",
                                "/api/v1/auth/sms-challenges",
                                "/api/v1/users/me",
                                "/api/v1/users/me/**",
                                "/api/v1/users/me/onboarding",
                                "/api/v1/users/search",
                                "/api/v1/friends",
                                "/api/v1/friend-requests",
                                "/api/v1/friend-requests/**",
                                "/api/v1/real-name-verifications/**",
                                "/api/v1/payment-authorizations",
                                "/internal/v1/**")
                        .ignoringRequestMatchers(transferRecipientCsrfExemption()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(org.springframework.http.HttpMethod.PUT,
                                "/api/v1/auth/merchant/password",
                                "/api/v1/auth/merchant/password/reset")
                        .access(audienceAndScope("merchant-api", "merchant.portal.write"))
                        .requestMatchers(
                                 "/login", "/login/**",
                                 "/session/logout", "/error",
                                 "/api/v1/csrf",
                                 "/api/v1/auth/consumer/**",
                                "/api/v1/auth/merchant/**",
                                 "/api/v1/auth/captchas/**", "/api/v1/auth/sms-challenges",
                                "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/accounts/**")
                        .access(audienceAndAnyScope("admin-api", "admin.account.read", "admin.account.write", "admin.backoffice.write"))
                        .requestMatchers("/api/v1/admin/summary")
                        .access(audienceAndScope("admin-api", "admin.account.read"))
                        .requestMatchers("/api/v1/admin/me/password")
                        .access(audienceAndScope("admin-api", "admin.portal"))
                        .requestMatchers("/api/v1/admin/action-audits")
                        .access(audienceAndScope("admin-api", "admin.audit.read"))
                        .requestMatchers("/api/v1/admin/login-audits")
                        .access(audienceAndScopeEither(
                                "management-api", "ops.audit.read",
                                "admin-api", "admin.audit.read"))
                        .requestMatchers("/api/v1/admin/logout-audits")
                        .access(audienceAndScope("management-api", "ops.portal"))
                        .requestMatchers("/api/v1/users/me/onboarding")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/friends", "/api/v1/users/search", "/api/v1/users/qr/**")
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/v1/friends/*")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/friend-requests")
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers("/api/v1/friend-requests/**")
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/real-name-verifications")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/real-name-verifications/**",
                                "/api/v1/users/me/capabilities")
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.PUT,
                                "/api/v1/users/me/payment-password")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/me")
                        // profile.write was present on tokens issued before profile.read was introduced.
                        // Treat the stronger scope as read-compatible so existing sessions keep working.
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/users/me/account-security")
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/users/me/application-authorizations",
                                "/api/v1/users/me/application-authorizations/*")
                        .access(audienceAndAnyScope(
                                "consumer-api", "identity.profile.read", "identity.profile.write"))
                        .requestMatchers(
                                "/api/v1/users/me/application-authorizations/**",
                                "/api/v1/users/me/phone-disclosure-challenges",
                                "/api/v1/users/me/phone-disclosure-verifications")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/users/me/phone-change-challenges",
                                "/api/v1/users/me/email-verification-challenges",
                                "/api/v1/users/me/payment-password-change-challenges",
                                "/api/v1/users/me/payment-password-change-challenges/*/verifications",
                                "/api/v1/users/me/payment-password-changes")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.PUT,
                                "/api/v1/users/me/phone", "/api/v1/users/me/email")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/v1/users/me/email")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/users/me")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/users/me/avatar-uploads")
                        .access(audienceAndScope("consumer-api", "identity.profile.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/v1/transfer-recipients/resolve")
                        .access(audienceAndScope("consumer-api", "payment.transfer.write"))
                        .requestMatchers("/api/v1/payment-authorizations")
                        .access(audienceAndScope(
                                "consumer-api",
                                "identity.payment-authorization.write"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/internal/v1/consumer-payment-profiles",
                                "/internal/v1/consumer-payment-profiles/**")
                        .access(audienceAndScope(
                                "identity-internal",
                               "identity.consumer-payment-profile.read"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/internal/v1/users/*/application-disclosures/*")
                        .access(audienceAndScope(
                                "identity-internal", "identity.application-disclosure.read"))
                       .requestMatchers(org.springframework.http.HttpMethod.POST,
                               "/internal/v1/agent/recipients/resolve-exact-mobile")
                       .access(agentDelegation(
                               "identity.agent.recipient.resolve", "RECIPIENT_RESOLUTION"))
                       .requestMatchers(org.springframework.http.HttpMethod.POST,
                               "/internal/v1/agent/contacts/resolve-exact")
                       .access(agentDelegation("agent.contact.read", "CONTACT_LOOKUP"))
                        .requestMatchers("/internal/v1/accounts/**")
                        .access(audienceAndScope(
                                "identity-internal",
                                "identity.account.manage"))
                        .requestMatchers("/internal/v1/**")
                        .access(audienceAndScope(
                                "identity-internal",
                                "identity.payment-authorization.verify"))
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(
                        jwt -> jwt.decoder(resourceApiJwtDecoder(jwkSource, issuer))
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .build();
    }

    static RequestMatcher transferRecipientCsrfExemption() {
        return PathPatternRequestMatcher.withDefaults().matcher(
                org.springframework.http.HttpMethod.POST,
                "/api/v1/transfer-recipients/resolve");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository clients,
            @Value("${minipay.identity.token-digest-pepper}") String tokenDigestPepper) {
        JdbcOAuth2AuthorizationService delegate = new JdbcOAuth2AuthorizationService(jdbcTemplate, clients);
        return new DigestingOAuth2AuthorizationService(delegate, jdbcTemplate, tokenDigestPepper);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients);
    }

    @Bean
    ApplicationRunner managementClientBootstrap(
            RegisteredClientRepository clients,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            @Value("${minipay.identity.management-client.client-id}") String clientId,
            @Value("${minipay.identity.management-client.client-secret}") String clientSecret,
            @Value("${minipay.identity.management-client.redirect-uri}") String redirectUri,
            @Value("${minipay.identity.management-client.post-logout-redirect-uri}") String postLogoutUri,
            @Value("${minipay.identity.admin-client.client-id}") String adminClientId,
            @Value("${minipay.identity.admin-client.client-secret}") String adminClientSecret,
            @Value("${minipay.identity.admin-client.redirect-uri}") String adminRedirectUri,
            @Value("${minipay.identity.admin-client.post-logout-redirect-uri}") String adminPostLogoutUri,
            @Value("${minipay.identity.android-client.client-id}") String androidClientId,
            @Value("${minipay.identity.android-client.redirect-uri}") String androidRedirectUri,
            @Value("${minipay.identity.merchant-bff-client.client-id}") String merchantBffClientId,
            @Value("${minipay.identity.merchant-bff-client.redirect-uri}") String merchantBffRedirectUri,
            @Value("${minipay.identity.internal-clients.payment-to-identity.client-id}") String paymentIdentityId,
            @Value("${minipay.identity.internal-clients.payment-to-identity.client-secret}") String paymentIdentitySecret,
            @Value("${minipay.identity.internal-clients.payment-to-wallet.client-id}") String paymentWalletId,
            @Value("${minipay.identity.internal-clients.payment-to-wallet.client-secret}") String paymentWalletSecret,
            @Value("${minipay.identity.internal-clients.wallet-to-identity.client-id}") String walletIdentityId,
            @Value("${minipay.identity.internal-clients.wallet-to-identity.client-secret}") String walletIdentitySecret,
            @Value("${minipay.identity.internal-clients.agent-to-payment.client-id}") String agentPaymentId,
            @Value("${minipay.identity.internal-clients.agent-to-payment.client-secret}") String agentPaymentSecret,
            @Value("${minipay.identity.internal-clients.agent-to-identity.client-id}") String agentIdentityId,
            @Value("${minipay.identity.internal-clients.agent-to-identity.client-secret}") String agentIdentitySecret,
            @Value("${minipay.identity.internal-clients.agent-delegation.client-id}") String agentDelegationId,
            @Value("${minipay.identity.internal-clients.agent-delegation.client-secret}") String agentDelegationSecret,
            @Value("${minipay.identity.internal-clients.identity-to-payment.client-id}") String identityPaymentId,
            @Value("${minipay.identity.internal-clients.identity-to-payment.client-secret}") String identityPaymentSecret,
            @Value("${minipay.identity.internal-clients.commerce-to-identity.client-id}") String commerceIdentityId,
            @Value("${minipay.identity.internal-clients.commerce-to-identity.client-secret}") String commerceIdentitySecret) {
        return arguments -> {
            RegisteredClient existingClient = clients.findByClientId(clientId);
            String encodedSecret = existingClient != null
                            && passwordEncoder.matches(clientSecret, existingClient.getClientSecret())
                    ? existingClient.getClientSecret()
                    : passwordEncoder.encode(clientSecret);
            RegisteredClient client = RegisteredClient.withId(
                            existingClient == null ? UUID.randomUUID().toString() : existingClient.getId())
                    .clientId(clientId)
                    .clientSecret(encodedSecret)
                    .clientName("MiniPay 运营平台")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri(redirectUri)
                    .postLogoutRedirectUri(postLogoutUri)
                    .scope(OidcScopes.OPENID)
                    .scope(OidcScopes.PROFILE)
                    .scope("ops.portal")
                    .scope("ops.audit.read")
                    .scope("ops.dashboard.read")
                    .scope("ops.merchant.read")
                    .scope("ops.merchant.write")
                    .scope("ops.application.read")
                    .scope("ops.application.write")
                    .scope("payment.refund.write")
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(false)
                            .setting("minipay.token-audience", "management-api")
                            .build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(Duration.ofMinutes(10))
                            .refreshTokenTimeToLive(Duration.ofHours(8))
                            .reuseRefreshTokens(false)
                            .build())
                    .build();
            replaceClient(clients, jdbcTemplate, existingClient, client);
            registerAdminClient(clients, jdbcTemplate, passwordEncoder, adminClientId,
                    adminClientSecret, adminRedirectUri, adminPostLogoutUri);
            registerAndroidClient(
                    clients,
                    jdbcTemplate,
                    androidClientId,
                    androidRedirectUri);
            registerMerchantBffClient(clients, jdbcTemplate, merchantBffClientId, merchantBffRedirectUri);
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    paymentIdentityId, paymentIdentitySecret,
                    "MiniPay Payment to Identity", "identity-internal",
                    "identity.payment-authorization.verify",
                    "identity.consumer-payment-profile.read",
                    "identity.account.manage");
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    paymentWalletId, paymentWalletSecret,
                    "MiniPay Payment to Wallet", "wallet-internal",
                    "wallet.tcc", "wallet.account.resolve", "wallet.posting.write");
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    walletIdentityId, walletIdentitySecret,
                    "MiniPay Wallet to Identity", "identity-internal",
                    "identity.consumer-payment-profile.read");
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    agentPaymentId, agentPaymentSecret,
                    "MiniPay Agent to Payment", "payment-internal", "payment.tool.invoke");
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    agentIdentityId, agentIdentitySecret,
                    "MiniPay Agent to Identity", "identity-internal",
                    "identity.consumer-payment-profile.read");
            registerAgentDelegationClient(
                    clients, jdbcTemplate, passwordEncoder,
                    agentDelegationId, agentDelegationSecret);
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    identityPaymentId, identityPaymentSecret,
                    "MiniPay Identity to Payment", "payment-internal", "payment.tool.invoke");
            registerInternalClient(
                    clients, jdbcTemplate, passwordEncoder,
                    commerceIdentityId, commerceIdentitySecret,
                    "MiniPay Commerce to Identity", "identity-internal",
                    "identity.application-disclosure.read");
        };
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(
            Environment environment,
            ResourceLoader resourceLoader,
            @Value("${minipay.identity.signing-private-key-location:}") String privateKeyLocation,
            @Value("${minipay.identity.signing-public-key-location:}") String publicKeyLocation) {
        try {
            if (!privateKeyLocation.isBlank() && !publicKeyLocation.isBlank()) {
                RSAPrivateKey privateKey = (RSAPrivateKey) RsaKeyConverters.pkcs8().convert(
                        resourceLoader.getResource(privateKeyLocation).getInputStream());
                RSAPublicKey publicKey = (RSAPublicKey) RsaKeyConverters.x509().convert(
                        resourceLoader.getResource(publicKeyLocation).getInputStream());
                RSAKey keyWithoutId = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
                RSAKey rsa = new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(keyWithoutId.computeThumbprint().toString())
                        .build();
                return new ImmutableJWKSet<>(new JWKSet(rsa));
            }
            if (!environment.acceptsProfiles(Profiles.of("demo-auth"))) {
                throw new IllegalStateException(
                        "JWT signing key locations are required outside the demo-auth profile");
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsa));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize signing key", exception);
        }
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(
            JWKSource<SecurityContext> jwkSource,
            OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(jwtCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator(),
                new RotatingRefreshTokenGenerator());
    }

    private JwtDecoder resourceApiJwtDecoder(
            JWKSource<SecurityContext> jwkSource,
            String issuer) {
        NimbusJwtDecoder decoder =
                (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", SecurityConfiguration::isResourceApiAudience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator));
        return decoder;
    }

    static boolean isResourceApiAudience(List<String> audience) {
        return audience != null && audience.stream().anyMatch(RESOURCE_API_AUDIENCES::contains);
    }

    /** Accept both OAuth space-delimited and JSON-array scope claims from Identity tokens. */
    static Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            Object rawScopes = jwt.getClaims().get("scope");
            Set<SimpleGrantedAuthority> authorities = new LinkedHashSet<>();
            if (rawScopes instanceof Collection<?> scopes) {
                scopes.stream().map(String::valueOf).filter(value -> !value.isBlank())
                        .map(value -> new SimpleGrantedAuthority("SCOPE_" + value))
                        .forEach(authorities::add);
            } else if (rawScopes instanceof String scopes) {
                for (String scope : scopes.split("\\s+")) {
                    if (!scope.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
                    }
                }
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @Value("${minipay.identity.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(
            AdminAccountRepository accounts,
            ConsumerAccountRepository consumers) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                if (AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType())
                        && context.getAuthorizationGrant()
                                instanceof OAuth2TokenExchangeAuthenticationToken exchange) {
                    String audience = exchange.getAudiences().iterator().next();
                    String purpose = (String) exchange.getAdditionalParameters()
                            .get(DelegationTokenExchangeValidator.PURPOSE_PARAMETER);
                    String runId = (String) exchange.getAdditionalParameters()
                            .get(DelegationTokenExchangeValidator.RUN_ID_PARAMETER);
                    Map<String, Object> subjectClaims = context.getAuthorization().getAccessToken().getClaims();
                    String deviceId = (String) subjectClaims.get("device_id");
                    String actorClientId = context.getRegisteredClient().getClientId();
                    String subjectUserId = context.getAuthorization().getPrincipalName();
                    UUID.fromString(subjectUserId);
                    Object claimedUserId = subjectClaims.get("user_id");
                    if (claimedUserId != null && !subjectUserId.equals(String.valueOf(claimedUserId))) {
                        throw new IllegalStateException("Token exchange subject user does not match authorization");
                    }
                    context.getClaims()
                            .subject(subjectUserId)
                            .audience(new ArrayList<>(List.of(audience)))
                            .id(UUID.randomUUID().toString())
                            .claim("azp", actorClientId)
                            .claim("act", Map.of("sub", actorClientId))
                            .claim("purpose", purpose)
                            .claim("run_id", runId)
                            .claim("device_id", deviceId)
                            .claim("user_id", subjectUserId);
                    return;
                }
                String audience = context.getRegisteredClient()
                        .getClientSettings()
                        .getSetting("minipay.token-audience");
                if (audience != null && !audience.isBlank()) {
                    context.getClaims().audience(new ArrayList<>(List.of(audience)));
                }
            }
            UUID userId;
            try {
                userId = UUID.fromString(context.getPrincipal().getName());
            } catch (IllegalArgumentException exception) {
                return;
            }
            AdminPrincipal admin = accounts.findPrincipal(userId).orElse(null);
            if (admin != null) {
                List<String> roles = admin.getAuthorities().stream()
                        .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", "").toLowerCase(Locale.ROOT))
                        .collect(Collectors.toCollection(ArrayList::new));
                context.getClaims()
                        .claim("roles", roles)
                        .claim("display_name", admin.displayName())
                        .claim("user_id", admin.userId().toString());
                return;
            }
            ConsumerPrincipal consumer = consumers.findActive(userId).orElse(null);
            if (consumer == null) {
                return;
            }
            List<String> consumerRoles = new ArrayList<>(List.of("consumer"));
            if (consumer.merchantOwner()) {
                consumerRoles.add("merchant_owner");
            }
            context.getClaims()
                    .claim("roles", consumerRoles)
                    .claim("display_name", consumer.displayName())
                    .claim("user_id", consumer.userId().toString())
                    .claim("pay_password_set", consumer.payPasswordSet())
                    .claim("onboarding_completed", consumer.onboardingCompleted())
                    .claim("real_name_status", consumer.realNameStatus())
                    .claim("real_name_verified", consumer.realNameVerified());
            if (context.getAuthorization() != null) {
                String deviceId = context.getAuthorization()
                        .getAttribute(ConsumerAuthorizationCodeService.DEVICE_ID_ATTRIBUTE);
                String sessionId = context.getAuthorization()
                        .getAttribute(ConsumerAuthorizationCodeService.SESSION_ID_ATTRIBUTE);
                if (deviceId != null) {
                    context.getClaims().claim("device_id", deviceId);
                }
                if (sessionId != null) {
                    context.getClaims().claim("session_id", sessionId);
                }
            }
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> audienceAndScope(
            String audience,
            String scope) {
        return audienceAndAnyScope(audience, scope);
    }

    static AuthorizationManager<RequestAuthorizationContext> audienceAndScopeEither(
            String firstAudience, String firstScope,
            String secondAudience, String secondScope) {
        return (authenticationSupplier, context) -> {
            org.springframework.security.core.Authentication authentication =
                    authenticationSupplier.get();
            if (!(authentication instanceof
                    org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            boolean firstGranted = jwt.getToken().getAudience().contains(firstAudience)
                    && jwt.getAuthorities().stream().anyMatch(authority ->
                            authority.getAuthority().equals("SCOPE_" + firstScope));
            boolean secondGranted = jwt.getToken().getAudience().contains(secondAudience)
                    && jwt.getAuthorities().stream().anyMatch(authority ->
                            authority.getAuthority().equals("SCOPE_" + secondScope));
            return new AuthorizationDecision(firstGranted || secondGranted);
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> audienceAndAnyScope(
            String audience,
            String... scopes) {
        return (authenticationSupplier, context) -> {
            org.springframework.security.core.Authentication authentication =
                    authenticationSupplier.get();
            if (!(authentication instanceof
                    org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)) {
                return new AuthorizationDecision(false);
            }
            boolean granted = jwt.getToken().getAudience().contains(audience)
                    && jwt.getAuthorities().stream()
                    .anyMatch(authority -> java.util.Arrays.stream(scopes)
                            .anyMatch(scope -> authority.getAuthority().equals("SCOPE_" + scope)));
            return new AuthorizationDecision(granted);
        };
    }

    private static void registerAdminClient(
            RegisteredClientRepository clients, JdbcTemplate jdbcTemplate, PasswordEncoder encoder,
            String clientId, String rawSecret, String redirectUri, String postLogoutUri) {
        RegisteredClient existing = clients.findByClientId(clientId);
        String encodedSecret = existing != null && encoder.matches(rawSecret, existing.getClientSecret())
                ? existing.getClientSecret() : encoder.encode(rawSecret);
        RegisteredClient client = RegisteredClient.withId(
                        existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(clientId).clientSecret(encodedSecret).clientName("MiniPay System Admin")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri).postLogoutRedirectUri(postLogoutUri)
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE)
                .scope("admin.portal").scope("admin.account.read").scope("admin.account.write")
                .scope("admin.backoffice.write").scope("admin.order.read")
                .scope("admin.wallet.read").scope("admin.audit.read")
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .setting("minipay.token-audience", "admin-api").build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(10))
                        .refreshTokenTimeToLive(Duration.ofHours(4)).reuseRefreshTokens(false).build())
                .build();
        replaceClient(clients, jdbcTemplate, existing, client);
    }

    private static void registerInternalClient(
            RegisteredClientRepository clients,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder encoder,
            String clientId,
            String rawSecret,
            String name,
            String audience,
            String... scopes) {
        RegisteredClient existing = clients.findByClientId(clientId);
        String encodedSecret = existing != null
                        && encoder.matches(rawSecret, existing.getClientSecret())
                ? existing.getClientSecret()
                : encoder.encode(rawSecret);
        RegisteredClient.Builder builder = RegisteredClient.withId(
                        existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(clientId)
                .clientSecret(encodedSecret)
                .clientName(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .setting("minipay.token-audience", audience)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build());
        for (String scope : scopes) {
            builder.scope(scope);
        }
        RegisteredClient client = builder.build();
        replaceClient(clients, jdbcTemplate, existing, client);
    }

    static AuthorizationManager<RequestAuthorizationContext> agentDelegation(
            String scope, String purpose) {
        return (authenticationSupplier, context) -> {
            if (!(authenticationSupplier.get() instanceof
                    org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken authentication)) {
                return new AuthorizationDecision(false);
            }
            Jwt jwt = authentication.getToken();
            boolean identifiersValid;
            try {
                UUID.fromString(jwt.getClaimAsString("run_id"));
                UUID.fromString(jwt.getClaimAsString("user_id"));
                identifiersValid = true;
            } catch (RuntimeException exception) {
                identifiersValid = false;
            }
            boolean granted = identifiersValid
                    && jwt.getAudience().contains("identity-internal")
                    && "minipay-agent-service".equals(jwt.getClaimAsString("azp"))
                    && purpose.equals(jwt.getClaimAsString("purpose"))
                    && jwt.getClaimAsString("device_id") != null
                    && authentication.getAuthorities().stream().anyMatch(
                    authority -> authority.getAuthority().equals("SCOPE_" + scope));
            return new AuthorizationDecision(granted);
        };
    }

    private static void registerAgentDelegationClient(
            RegisteredClientRepository clients,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder encoder,
            String clientId,
            String rawSecret) {
        RegisteredClient existing = clients.findByClientId(clientId);
        String encodedSecret = existing != null
                        && encoder.matches(rawSecret, existing.getClientSecret())
                ? existing.getClientSecret()
                : encoder.encode(rawSecret);
        RegisteredClient client = RegisteredClient.withId(
                        existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(clientId)
                .clientSecret(encodedSecret)
                .clientName("MiniPay Agent delegated tool access")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .scope("agent.contact.read")
                .scope("identity.agent.recipient.resolve")
                .scope("wallet.agent.summary")
                .scope("wallet.agent.bills.read")
                .scope("wallet.agent.bills.aggregate")
                .scope("payment.agent.transfer.prepare")
                .scope("payment.agent.transfer.read")
                .scope("commerce.agent.catalog.read")
                .scope("commerce.agent.cart.write")
                .scope("commerce.agent.checkout.prepare")
                .scope("commerce.agent.order.read")
                .scope("commerce.agent.cancel.prepare")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(2))
                        .build())
                .build();
        replaceClient(clients, jdbcTemplate, existing, client);
    }

    private static void registerAndroidClient(
            RegisteredClientRepository clients,
            JdbcTemplate jdbcTemplate,
            String clientId,
            String redirectUri) {
        RegisteredClient existing = clients.findByClientId(clientId);
        RegisteredClient client = RegisteredClient.withId(
                        existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(clientId)
                .clientName("MiniPay Android")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope("identity.profile.read")
                .scope("identity.profile.write")
                .scope("identity.payment-authorization.write")
                .scope("payment.transfer.read")
                .scope("payment.transfer.write")
                .scope("payment.recharge.read")
                .scope("payment.recharge.write")
                .scope("payment.withdrawal.read")
                .scope("payment.withdrawal.write")
                .scope("payment.bank-card.read")
                .scope("payment.bank-card.write")
                .scope("payment.order.read")
                .scope("payment.order.write")
                .scope("payment.collection-code.read")
                .scope("merchant.portal.read")
                .scope("merchant.portal.write")
                .scope("wallet.read")
                .scope("wallet.write")
                .scope("agent.conversation")
                .scope("commerce.use")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .setting("minipay.token-audience", "consumer-api")
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        replaceClient(clients, jdbcTemplate, existing, client);
    }

    private static void registerMerchantBffClient(
            RegisteredClientRepository clients,
            JdbcTemplate jdbcTemplate,
            String clientId,
            String redirectUri) {
        RegisteredClient existing = clients.findByClientId(clientId);
        RegisteredClient client = RegisteredClient.withId(
                        existing == null ? UUID.randomUUID().toString() : existing.getId())
                .clientId(clientId)
                .clientName("MiniPay Merchant BFF")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope("merchant.portal.read")
                .scope("merchant.portal.write")
                .scope("payment.collection-code.read")
                .scope("wallet.read")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .setting("minipay.token-audience", "merchant-api")
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofSeconds(60))
                        // 商户端无自动刷新，Access Token 调长为 12 小时，避免登录后频繁失效。
                        .accessTokenTimeToLive(Duration.ofHours(12))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        replaceClient(clients, jdbcTemplate, existing, client);
    }

    private static void replaceClient(
            RegisteredClientRepository clients,
            JdbcTemplate jdbcTemplate,
            RegisteredClient existing,
            RegisteredClient replacement) {
        if (existing != null) {
            jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", existing.getId());
        }
        clients.save(replacement);
    }
}
