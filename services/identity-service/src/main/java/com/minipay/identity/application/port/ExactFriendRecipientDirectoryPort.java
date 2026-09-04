package com.minipay.identity.application.port;

import java.util.List;
import java.util.UUID;

public interface ExactFriendRecipientDirectoryPort {
    List<FriendRecipientRecord> findExactMatches(
            UUID ownerUserId, String nickname, byte[] legalNameHash, int limit);

    record FriendRecipientRecord(
            UUID userId,
            String nickname,
            String phoneMasked,
            String legalNameMasked,
            boolean nicknameMatched,
            boolean legalNameMatched) {
    }
}
