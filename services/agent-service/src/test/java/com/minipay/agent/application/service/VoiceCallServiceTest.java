package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import com.minipay.agent.infrastructure.realtime.RealtimeSessionRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class VoiceCallServiceTest {
    @Test void refusesCallWhenPeerIsOffline() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ChatRepository chats = mock(ChatRepository.class);
        RealtimeSessionRegistry realtime = mock(RealtimeSessionRegistry.class);
        UUID caller = UUID.randomUUID(), callee = UUID.randomUUID();
        when(chats.findDirectPeer(caller, "conv_1")).thenReturn(Optional.of(callee));
        when(realtime.isOnline(callee)).thenReturn(false);
        VoiceCallService service = new VoiceCallService(jdbc, chats, realtime, new ObjectMapper(), Duration.ofSeconds(30), "stun:example.org", "", Duration.ofHours(1));
        assertThatThrownBy(() -> service.create(caller, "conv_1"))
                .isInstanceOf(VoiceCallService.CallException.class).hasMessage("CALLEE_OFFLINE");
    }
}
