package com.minipay.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void acceptsMerchantApiTokensForIdentityMerchantEndpoints() {
        assertThat(SecurityConfiguration.isResourceApiAudience(List.of("merchant-api")))
                .isTrue();
    }

    @Test
    void rejectsUnknownResourceAudience() {
        assertThat(SecurityConfiguration.isResourceApiAudience(List.of("unknown-api")))
                .isFalse();
    }

    @Test
    void mapsDelegatedArrayScopesToAuthorities() {
        Jwt jwt = Jwt.withTokenValue("delegated")
                .header("alg", "none")
                .subject("0198f200-0000-7000-8000-000000000001")
                .claim("scope", List.of("agent.contact.read"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authentication = SecurityConfiguration.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("SCOPE_agent.contact.read");
    }

    @Test
    void mapsSpaceDelimitedScopesToAuthorities() {
        Jwt jwt = Jwt.withTokenValue("delegated")
                .header("alg", "none")
                .subject("0198f200-0000-7000-8000-000000000001")
                .claim("scope", "agent.contact.read identity.agent.recipient.resolve")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authentication = SecurityConfiguration.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("SCOPE_agent.contact.read", "SCOPE_identity.agent.recipient.resolve");
    }

    @Test
    void acceptsBoundContactLookupDelegationAndRejectsWrongPurpose() {
        UUID userId = UUID.fromString("0198f400-0000-7000-8000-000000000006");
        UUID runId = UUID.fromString("0198f400-0000-7000-8000-000000000005");
        Jwt jwt = Jwt.withTokenValue("delegated")
                .header("alg", "none")
                .subject(userId.toString())
                .audience(List.of("identity-internal"))
                .claim("azp", "minipay-agent-service")
                .claim("run_id", runId.toString())
                .claim("user_id", userId.toString())
                .claim("device_id", "android-1")
                .claim("purpose", "CONTACT_LOOKUP")
                .claim("scope", List.of("agent.contact.read"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        var authentication = SecurityConfiguration.jwtAuthenticationConverter().convert(jwt);

        var accepted = SecurityConfiguration.agentDelegation("agent.contact.read", "CONTACT_LOOKUP")
                .check(() -> authentication, null);
        var rejected = SecurityConfiguration.agentDelegation("agent.contact.read", "RECIPIENT_RESOLUTION")
                .check(() -> authentication, null);

        assertThat(accepted).isNotNull();
        assertThat(accepted.isGranted()).isTrue();
        assertThat(rejected).isNotNull();
        assertThat(rejected.isGranted()).isFalse();
    }

    @Test
    void loginAuditsAcceptBothOpsAndSystemAdminTokens() {
        var access = SecurityConfiguration.audienceAndScopeEither(
                "management-api", "ops.audit.read",
                "admin-api", "admin.audit.read");

        assertThat(access.check(() -> token("management-api", "ops.audit.read"), null).isGranted())
                .isTrue();
        assertThat(access.check(() -> token("admin-api", "admin.audit.read"), null).isGranted())
                .isTrue();
        assertThat(access.check(() -> token("admin-api", "admin.account.read"), null).isGranted())
                .isFalse();
    }

    private static org.springframework.security.core.Authentication token(
            String audience, String scope) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("0198f200-0000-7000-8000-000000000001")
                .audience(List.of(audience))
                .claim("scope", List.of(scope))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return SecurityConfiguration.jwtAuthenticationConverter().convert(jwt);
    }
}
