package com.minipay.agent.application.port;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface DelegatedTokenProvider {
    DelegatedToken exchange(TokenExchangeRequest request);

    record TokenExchangeRequest(
            String subjectToken,
            UUID runId,
            String audience,
            Set<String> scopes,
            String purpose) {
        public TokenExchangeRequest {
            scopes = Set.copyOf(scopes);
        }
    }

    record DelegatedToken(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            Set<String> scopes) {
        public DelegatedToken {
            scopes = Set.copyOf(scopes);
        }
    }
}
