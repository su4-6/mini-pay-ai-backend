package com.minipay.consumerbff.infrastructure.session;

import com.minipay.consumerbff.application.port.SessionTokenStore;
import com.minipay.consumerbff.domain.security.Pkce;
import com.minipay.consumerbff.domain.session.ConsumerSession;
import com.minipay.consumerbff.domain.session.ConsumerTokens;
import com.minipay.consumerbff.domain.session.LoginChallenge;
import java.time.Duration;
import java.util.UUID;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Redis WebSession implementation of the BFF session port.
 *
 * <p>Only primitives and small strings are written into the session so Spring Session Redis
 * serialization stays stable. Tokens live under dedicated attribute names and are never returned by
 * any controller.
 */
public class WebSessionTokenStore implements SessionTokenStore {

    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);

    private final Mono<WebSession> session;

    public WebSessionTokenStore(Mono<WebSession> session) {
        this.session = session;
    }

    @Override
    public Mono<ConsumerTokens> loadTokens() {
        return session.map(current -> new ConsumerTokens(
                        current.getAttribute(ConsumerSessionAttributes.ACCESS_TOKEN),
                        current.getAttribute(ConsumerSessionAttributes.REFRESH_TOKEN)))
                .defaultIfEmpty(new ConsumerTokens(null, null));
    }

    @Override
    public Mono<Void> storeTokens(ConsumerTokens tokens) {
        return session.doOnNext(current -> {
                    current.getAttributes().put(
                            ConsumerSessionAttributes.ACCESS_TOKEN, tokens.accessToken());
                    if (tokens.refreshToken() != null) {
                        current.getAttributes().put(
                                ConsumerSessionAttributes.REFRESH_TOKEN, tokens.refreshToken());
                    }
                })
                .then();
    }

    @Override
    public Mono<Void> rotateSessionId() {
        return session.doOnNext(current -> {
                    current.changeSessionId();
                    current.setMaxIdleTime(SESSION_TIMEOUT);
                })
                .then();
    }

    @Override
    public Mono<Void> storeConsumerSession(ConsumerSession value) {
        return session.doOnNext(current -> {
                    current.getAttributes().put(
                            ConsumerSessionAttributes.CONSUMER_ID, value.consumerId().toString());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.MASKED_PHONE, value.maskedPhone());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.DISPLAY_NAME, value.displayName());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.PAY_PASSWORD_SET, value.payPasswordSet());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.ONBOARDING_REQUIRED,
                            value.onboardingRequired());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.REAL_NAME_STATUS, value.realNameStatus());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.REAL_NAME_VERIFIED, value.realNameVerified());
                })
                .then();
    }

    @Override
    public Mono<ConsumerSession> loadConsumerSession() {
        return session.handle((current, sink) -> {
            String userId = current.getAttribute(ConsumerSessionAttributes.CONSUMER_ID);
            if (userId == null) {
                return;
            }
            UUID consumerId;
            try {
                consumerId = UUID.fromString(userId);
            } catch (RuntimeException exception) {
                return;
            }
            sink.next(new ConsumerSession(
                    consumerId,
                    attribute(current, ConsumerSessionAttributes.MASKED_PHONE, ""),
                    attribute(current, ConsumerSessionAttributes.DISPLAY_NAME, "米灵用户"),
                    Boolean.TRUE.equals(
                            current.getAttribute(ConsumerSessionAttributes.PAY_PASSWORD_SET)),
                    Boolean.TRUE.equals(
                            current.getAttribute(ConsumerSessionAttributes.ONBOARDING_REQUIRED)),
                    attribute(current, ConsumerSessionAttributes.REAL_NAME_STATUS, "UNVERIFIED"),
                    Boolean.TRUE.equals(
                            current.getAttribute(ConsumerSessionAttributes.REAL_NAME_VERIFIED))));
        });
    }

    @Override
    public Mono<Void> storeLoginChallenge(LoginChallenge challenge) {
        return session.doOnNext(current -> {
                    current.getAttributes().put(
                            ConsumerSessionAttributes.LOGIN_VERIFIER, challenge.pkce().verifier());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.LOGIN_CHALLENGE, challenge.challengeId());
                    current.getAttributes().put(
                            ConsumerSessionAttributes.LOGIN_DEVICE, challenge.deviceId());
                })
                .then();
    }

    @Override
    public Mono<LoginChallenge> loadLoginChallenge() {
        return session.handle((current, sink) -> {
            String verifier = current.getAttribute(ConsumerSessionAttributes.LOGIN_VERIFIER);
            String challengeId = current.getAttribute(ConsumerSessionAttributes.LOGIN_CHALLENGE);
            String deviceId = current.getAttribute(ConsumerSessionAttributes.LOGIN_DEVICE);
            if (verifier == null || challengeId == null || deviceId == null) {
                return;
            }
            sink.next(new LoginChallenge(
                    new Pkce(verifier, Pkce.challengeOf(verifier)), deviceId, challengeId));
        });
    }

    @Override
    public Mono<String> loadDeviceId() {
        return session.handle((current, sink) -> {
            String deviceId = current.getAttribute(ConsumerSessionAttributes.LOGIN_DEVICE);
            if (deviceId != null) {
                sink.next(deviceId);
            }
        });
    }

    @Override
    public Mono<Void> storePreparedTransfer(String intentId, long amountFen) {
        return session.doOnNext(current -> {
                    current.getAttributes().put(
                            ConsumerSessionAttributes.PREPARED_TRANSFER_INTENT, intentId);
                    current.getAttributes().put(
                            ConsumerSessionAttributes.PREPARED_TRANSFER_AMOUNT, amountFen);
                })
                .then();
    }

    @Override
    public Mono<Void> storePreparedPayment(String paymentOrderId, long amountFen) {
        return session.doOnNext(current -> {
                    current.getAttributes().put(
                            ConsumerSessionAttributes.PREPARED_PAYMENT_ORDER, paymentOrderId);
                    current.getAttributes().put(
                            ConsumerSessionAttributes.PREPARED_PAYMENT_AMOUNT, amountFen);
                })
                .then();
    }

    @Override
    public Mono<Long> loadPreparedAmount(String intentId) {
        return session.handle((current, sink) -> {
            String preparedTransfer =
                    current.getAttribute(ConsumerSessionAttributes.PREPARED_TRANSFER_INTENT);
            if (intentId != null && intentId.equals(preparedTransfer)) {
                Object transferAmount =
                        current.getAttribute(ConsumerSessionAttributes.PREPARED_TRANSFER_AMOUNT);
                if (transferAmount instanceof Number number) {
                    sink.next(number.longValue());
                    return;
                }
            }
            String preparedPayment =
                    current.getAttribute(ConsumerSessionAttributes.PREPARED_PAYMENT_ORDER);
            Object paymentAmount =
                    current.getAttribute(ConsumerSessionAttributes.PREPARED_PAYMENT_AMOUNT);
            if (intentId != null
                    && intentId.equals(preparedPayment)
                    && paymentAmount instanceof Number number) {
                sink.next(number.longValue());
            }
        });
    }

    @Override
    public Mono<Void> clearLoginChallenge() {
        return session.doOnNext(current -> {
                    current.getAttributes().remove(ConsumerSessionAttributes.LOGIN_VERIFIER);
                    current.getAttributes().remove(ConsumerSessionAttributes.LOGIN_CHALLENGE);
                })
                .then();
    }

    @Override
    public Mono<Void> invalidate() {
        return session.flatMap(WebSession::invalidate);
    }

    private static String attribute(WebSession session, String name, String fallback) {
        String value = session.getAttribute(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
