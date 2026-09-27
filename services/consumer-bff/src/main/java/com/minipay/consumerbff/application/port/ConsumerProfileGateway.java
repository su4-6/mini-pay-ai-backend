package com.minipay.consumerbff.application.port;

import com.minipay.consumerbff.domain.identity.ConsumerIdentity;
import reactor.core.publisher.Mono;

/**
 * Resolves the credential-free consumer summary behind an access token.
 *
 * <p>Login needs this because Identity's code-verify response carries the <em>full</em> mobile and
 * no nickname: the BFF keeps only the masked number it derives itself and reads the nickname from
 * the profile document. A profile read failure must never fail the login.
 */
public interface ConsumerProfileGateway {

    /**
     * @param userId the consumer UUID taken from the code-verify response
     * @param fallback masked mobile to use when the profile document is unavailable
     */
    Mono<ConsumerIdentity> load(
            IdentityAuthorizationGateway.OAuthTokenSet tokens,
            String userId,
            String fallbackMaskedPhone,
            boolean payPasswordSet,
            boolean onboardingRequired,
            String realNameStatus,
            boolean realNameVerified,
            String requestId);

    /** Resolves the display name of an already established session; empty when unavailable. */
    Mono<String> loadDisplayName(IdentityAuthorizationGateway.OAuthTokenSet tokens, String requestId);
}
