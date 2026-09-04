package com.minipay.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class IdentityInternalClientTest {
    @Test
    void identifiesRejectedClientCredentialsWithoutExposingTheCredential() {
        assertThat(IdentityInternalClient.serviceTokenFailureCode(response(HttpStatus.UNAUTHORIZED)))
                .isEqualTo("IDENTITY_CLIENT_CREDENTIALS_REJECTED");
    }

    @Test
    void identifiesMissingInternalAuthorizationScope() {
        assertThat(IdentityInternalClient.verifyFailureCode(response(HttpStatus.FORBIDDEN)))
                .isEqualTo("IDENTITY_AUTHORIZATION_ACCESS_DENIED");
    }

    @Test
    void keepsTemporaryIdentityFailuresRetryable() {
        assertThat(IdentityInternalClient.serviceTokenFailureCode(
                HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8)))
                .isEqualTo("IDENTITY_AUTHORIZATION_SERVICE_UNAVAILABLE");
    }

    private static HttpClientErrorException response(HttpStatus status) {
        return HttpClientErrorException.create(
                status, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
