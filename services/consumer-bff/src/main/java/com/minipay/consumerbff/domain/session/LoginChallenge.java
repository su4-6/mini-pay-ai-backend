package com.minipay.consumerbff.domain.session;

import com.minipay.consumerbff.domain.security.Pkce;

/**
 * Server-side state of an in-flight SMS login. Kept in the WebSession so the PKCE verifier never
 * travels to the browser and cannot be replayed from another session.
 */
public record LoginChallenge(Pkce pkce, String deviceId, String challengeId) {
}
