package com.minipay.consumerbff.application.port;

import com.minipay.consumerbff.domain.session.ConsumerSession;
import com.minipay.consumerbff.domain.session.ConsumerTokens;
import com.minipay.consumerbff.domain.session.LoginChallenge;
import reactor.core.publisher.Mono;

/**
 * Outbound port over the Redis WebSession. Implemented by infrastructure; the application layer
 * only sees credential-free session state plus the server-side tokens.
 */
public interface SessionTokenStore {

    Mono<ConsumerTokens> loadTokens();

    Mono<Void> storeTokens(ConsumerTokens tokens);

    /**
     * Rotates the session identifier after a successful login so session fixation before
     * authentication cannot survive into the authenticated session.
     */
    Mono<Void> rotateSessionId();

    Mono<Void> storeConsumerSession(ConsumerSession session);

    Mono<ConsumerSession> loadConsumerSession();

    Mono<Void> storeLoginChallenge(LoginChallenge challenge);

    Mono<LoginChallenge> loadLoginChallenge();

    /** Device id that Identity bound into the current access token; payment requires it. */
    Mono<String> loadDeviceId();

    /** Remembers the amount prepared for a transfer intent so confirmation can be bound to it. */
    Mono<Void> storePreparedTransfer(String intentId, long amountFen);

    /**
     * Same guard for a merchant payment order (扫码付款): the wallet debit is bounded by the amount
     * this session prepared, so a tampered or stale confirmation cannot authorize another amount.
     */
    Mono<Void> storePreparedPayment(String paymentOrderId, long amountFen);

    /**
     * Amount authorized for a prepared intent or payment order, or empty when it was not prepared by
     * this session. Used to reject a tampered or stale confirmation amount.
     */
    Mono<Long> loadPreparedAmount(String intentId);

    Mono<Void> clearLoginChallenge();

    /** Clears the session completely. The WebSession id resolver issues a fresh cookie. */
    Mono<Void> invalidate();
}
