package com.minipay.agent.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.service.ChatMediaService;
import com.minipay.agent.application.service.GroupAvatarService;
import com.minipay.agent.application.service.VoiceMediaService;
import com.minipay.agent.domain.model.ChatMessage;
import com.minipay.agent.infrastructure.client.IdentityProfileClient;
import com.minipay.agent.infrastructure.client.PaymentTransferVerificationClient;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import com.minipay.agent.infrastructure.realtime.RealtimeSessionRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class ChatControllerTransferTest {
    private final ChatRepository chats = mock(ChatRepository.class);
    private final PaymentTransferVerificationClient transfers = mock(PaymentTransferVerificationClient.class);
    private final IdentityProfileClient profiles = mock(IdentityProfileClient.class);
    private final RealtimeSessionRegistry realtime = mock(RealtimeSessionRegistry.class);
    private final ChatController controller = new ChatController(
            chats, mock(VoiceMediaService.class), mock(ChatMediaService.class), realtime,
            transfers, mock(GroupAvatarService.class), profiles);

    @Test
    void deletesConversationOnlyForAuthenticatedParticipant() {
        UUID userId = UUID.randomUUID();
        when(chats.canAccessConversation(userId, "conv_direct")).thenReturn(true);

        controller.deleteConversation("conv_direct", jwt(userId));

        verify(chats).deleteConversationForUser(userId, "conv_direct");
    }

    @Test
    void rejectsConversationDeletionByNonParticipant() {
        UUID userId = UUID.randomUUID();
        when(chats.canAccessConversation(userId, "conv_private")).thenReturn(false);

        assertThatThrownBy(() -> controller.deleteConversation("conv_private", jwt(userId)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsSucceededTransferToDirectConversationPeerAndUsesPaymentAmount() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String conversationId = "conv_direct";
        when(chats.canAccessConversation(sender, conversationId)).thenReturn(true);
        when(chats.findDirectPeer(sender, conversationId)).thenReturn(Optional.of(receiver));
        when(transfers.requireSucceeded("access-token", transferId)).thenReturn(
                new PaymentTransferVerificationClient.TransferReceipt(
                        transferId, UUID.randomUUID(), receiver, 200_000, "SUCCEEDED", null, Instant.now()));
        when(profiles.findProfiles(any())).thenReturn(Map.of(
                sender, new IdentityProfileClient.Profile(sender, "郑逐玲", null, null),
                receiver, new IdentityProfileClient.Profile(receiver, "逐玲", null, null)));
        when(chats.findConversationParticipants(conversationId)).thenReturn(List.of());
        when(chats.insertMessage(any())).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            return new ChatMessage(1L, message.conversationId(), message.senderId(), message.senderType(),
                    message.content(), message.messageType(), message.transferAmount(), message.transferStatus(),
                    message.transferDirection(), message.transferId(), message.transferTargetUserId(),
                    message.voiceMediaId(), message.voiceDurationMs(), message.mediaId(), message.mediaKind(),
                    message.mediaContentType(), message.mediaWidth(), message.mediaHeight(), message.mediaDurationMs(),
                    message.callId(), message.callStatus(), message.callDurationSeconds(), message.createdAt());
        });

        ChatController.MessageResponse response = controller.sendMessage(conversationId,
                new ChatController.SendMessageRequest("ignored", "Transfer", "0.01", "FAILED", "Incoming",
                        transferId, receiver, null, null, null, null), jwt(sender));

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chats).insertMessage(saved.capture());
        assertThat(saved.getValue().transferAmount()).isEqualTo("2000.00");
        assertThat(saved.getValue().content()).isEqualTo("转账给逐玲");
        assertThat(response.transferDirection()).isEqualTo("Outgoing");
        assertThat(response.content()).isEqualTo("转账给逐玲");

        when(chats.canAccessConversation(receiver, conversationId)).thenReturn(true);
        ChatMessage sent = saved.getValue();
        ChatMessage persisted = new ChatMessage(1L, sent.conversationId(), sent.senderId(), sent.senderType(),
                sent.content(), sent.messageType(), sent.transferAmount(), sent.transferStatus(),
                sent.transferDirection(), sent.transferId(), sent.transferTargetUserId(), sent.voiceMediaId(),
                sent.voiceDurationMs(), sent.mediaId(), sent.mediaKind(), sent.mediaContentType(), sent.mediaWidth(),
                sent.mediaHeight(), sent.mediaDurationMs(), sent.callId(), sent.callStatus(),
                sent.callDurationSeconds(), sent.createdAt());
        when(chats.findMessages(receiver, conversationId, 50, 0)).thenReturn(List.of(persisted));
        when(chats.countMessages(receiver, conversationId)).thenReturn(1);
        ChatController.MessageResponse incoming = controller.listMessages(conversationId, 50, 0, jwt(receiver))
                .messages().getFirst();
        assertThat(incoming.transferDirection()).isEqualTo("Incoming");
        assertThat(incoming.content()).isEqualTo("郑逐玲转账给你");
    }

    @Test
    void rejectsTransferWhenTargetIsNotDirectConversationPeer() {
        UUID sender = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID forgedTarget = UUID.randomUUID();
        when(chats.canAccessConversation(sender, "conv_direct")).thenReturn(true);
        when(chats.findDirectPeer(sender, "conv_direct")).thenReturn(Optional.of(peer));

        assertThatThrownBy(() -> controller.sendMessage("conv_direct",
                new ChatController.SendMessageRequest("ignored", "Transfer", null, null, null,
                        UUID.randomUUID(), forgedTarget, null, null, null, null), jwt(sender)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INVALID_DIRECT_TRANSFER");
    }

    private static Jwt jwt(UUID subject) {
        return Jwt.withTokenValue("access-token")
                .header("alg", "none")
                .subject(subject.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
