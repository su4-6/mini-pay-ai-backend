package com.minipay.consumerbff.application.port;

import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Authenticated consumer calls against Identity Service that are not part of login. These require
 * the consumer access token of the current WebSession.
 */
public interface IdentityConsumerGateway {

    /**
     * Exchanges the in-memory payment password for a 60-second single-use authorization token bound
     * to the consumer, subject, amount and device. The password is used for this call only and is
     * never persisted, logged or echoed.
     */
    Mono<IssuedPaymentAuthorization> issuePaymentAuthorization(
            String accessToken,
            String idempotencyKey,
            String subjectType,
            String subjectId,
            long amountCent,
            String deviceId,
            String paymentPassword,
            String requestId);

    Mono<Void> setInitialPaymentPassword(
            WebSession session, String accessToken, String paymentPassword, String requestId);

    record IssuedPaymentAuthorization(String authorizationId, String paymentAuthToken) {

        @Override
        public String toString() {
            return "IssuedPaymentAuthorization[authorizationId=" + authorizationId
                    + ", paymentAuthToken=***]";
        }
    }
}
