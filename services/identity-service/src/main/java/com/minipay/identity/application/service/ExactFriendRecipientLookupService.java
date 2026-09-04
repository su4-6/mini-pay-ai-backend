package com.minipay.identity.application.service;

import com.minipay.identity.application.port.ExactFriendRecipientDirectoryPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExactFriendRecipientLookupService {
    private static final int MAX_MATCHES = 10;

    private final ExactFriendRecipientDirectoryPort directory;
    private final TransferRecipientLookupRateLimiter rateLimiter;
    private final byte[] hmacKey;

    public ExactFriendRecipientLookupService(
            ExactFriendRecipientDirectoryPort directory,
            TransferRecipientLookupRateLimiter rateLimiter,
            @Value("${minipay.identity.real-name.hmac-key}") String hmacKey) {
        if (hmacKey == null || hmacKey.length() < 32) {
            throw new IllegalStateException("Real-name HMAC key must contain at least 32 characters");
        }
        this.directory = directory;
        this.rateLimiter = rateLimiter;
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    public List<FriendRecipientView> resolve(UUID requesterUserId, String rawQuery, String clientAddress) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty() || query.codePointCount(0, query.length()) > 64) {
            throw new IllegalArgumentException("Friend recipient query is invalid");
        }
        rateLimiter.check(requesterUserId, clientAddress);
        return directory.findExactMatches(requesterUserId, query, hmac(query), MAX_MATCHES).stream()
                .map(record -> new FriendRecipientView(
                        record.userId(), record.nickname(), record.phoneMasked(),
                        record.legalNameMasked(), record.legalNameMasked() != null,
                        record.nicknameMatched(), record.legalNameMatched()))
                .toList();
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    public record FriendRecipientView(
            UUID recipientUserId,
            String nickname,
            String phoneMasked,
            String legalNameMasked,
            boolean verified,
            boolean nicknameMatched,
            boolean legalNameMatched) {
    }
}
