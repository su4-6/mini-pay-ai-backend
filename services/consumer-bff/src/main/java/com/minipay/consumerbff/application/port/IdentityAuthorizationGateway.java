package com.minipay.consumerbff.application.port;

import com.minipay.consumerbff.domain.identity.SmsChallenge;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Public (unauthenticated) consumer-authentication calls against Identity Service.
 *
 * <p>Mirrors the sequence the Android client already uses: {@code POST /api/v1/auth/consumer/code/send},
 * {@code POST /api/v1/auth/consumer/code/verify} then the OAuth token endpoint. The BFF performs
 * these steps server side with its own PKCE pair, so no token ever reaches the browser.
 */
public interface IdentityAuthorizationGateway {

    Mono<SmsChallenge> sendSmsCode(String mobile, String requestId);

    Mono<IssuedAuthorizationCode> verifySmsCode(
            String challengeId,
            String code,
            String clientId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String deviceId,
            String requestId);

    Mono<OAuthTokenSet> exchangeCode(String code, String codeVerifier, String requestId);

    Mono<OAuthTokenSet> refresh(String refreshToken, String requestId);

    Mono<Void> revoke(String refreshToken, String requestId);

    record IssuedAuthorizationCode(
            String authorizationCode,
            Instant expiresAt,
            String userId,
            String rawMobile,
            boolean payPasswordSet,
            boolean onboardingRequired,
            String realNameStatus,
            boolean realNameVerified) {

        /** The upstream response carries the full mobile; keep it out of logs and error messages. */
        @Override
        public String toString() {
            return "IssuedAuthorizationCode[authorizationCode=***, userId=" + userId
                    + ", rawMobile=***, payPasswordSet=" + payPasswordSet
                    + ", onboardingRequired=" + onboardingRequired
                    + ", realNameStatus=" + realNameStatus
                    + ", realNameVerified=" + realNameVerified + "]";
        }
    }

    record OAuthTokenSet(String accessToken, String refreshToken, long expiresInSeconds) {

        @Override
        public String toString() {
            return "OAuthTokenSet[accessToken=***, refreshToken="
                    + (refreshToken == null || refreshToken.isBlank() ? "absent" : "***")
                    + ", expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}
