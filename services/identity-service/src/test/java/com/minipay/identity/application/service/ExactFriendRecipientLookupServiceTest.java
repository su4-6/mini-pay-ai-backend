package com.minipay.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.identity.application.port.ExactFriendRecipientDirectoryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExactFriendRecipientLookupServiceTest {
    private static final UUID OWNER = UUID.fromString("0195f4f0-95be-7000-8000-000000000001");
    private static final UUID FRIEND = UUID.fromString("0195f4f0-95be-7000-8000-000000000002");

    @Mock ExactFriendRecipientDirectoryPort directory;
    @Mock TransferRecipientLookupRateLimiter rateLimiter;

    @Test
    void resolvesOnlyMinimalMaskedFriendData() {
        when(directory.findExactMatches(eq(OWNER), eq("张三"), any(byte[].class), eq(10)))
                .thenReturn(List.of(new ExactFriendRecipientDirectoryPort.FriendRecipientRecord(
                        FRIEND, "老张", "138****0000", "张*", false, true)));
        ExactFriendRecipientLookupService service = new ExactFriendRecipientLookupService(
                directory, rateLimiter, "0123456789abcdef0123456789abcdef");

        var result = service.resolve(OWNER, " 张三 ", "agent-device:test");

        assertThat(result).containsExactly(new ExactFriendRecipientLookupService.FriendRecipientView(
                FRIEND, "老张", "138****0000", "张*", true, false, true));
        verify(rateLimiter).check(OWNER, "agent-device:test");
        ArgumentCaptor<byte[]> digest = ArgumentCaptor.forClass(byte[].class);
        verify(directory).findExactMatches(eq(OWNER), eq("张三"), digest.capture(), eq(10));
        assertThat(digest.getValue()).hasSize(32);
    }
}
