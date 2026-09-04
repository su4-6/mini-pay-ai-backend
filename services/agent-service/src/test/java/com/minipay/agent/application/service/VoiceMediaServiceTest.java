package com.minipay.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minipay.agent.application.port.VoiceObjectStorage;
import com.minipay.agent.infrastructure.persistence.ChatRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class VoiceMediaServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatRepository chats = mock(ChatRepository.class);
    private final VoiceObjectStorage storage = mock(VoiceObjectStorage.class);
    private final VoiceMediaService service = new VoiceMediaService(jdbc, chats, storage, Duration.ofMinutes(10), Duration.ofMinutes(5));

    @Test void rejectsOversizedAudioBeforeCreatingObject() {
        UUID user = UUID.randomUUID();
        when(chats.canAccessConversation(user, "conv_1")).thenReturn(true);
        assertThatThrownBy(() -> service.createUpload(user, "conv_1", "audio/mp4", VoiceMediaService.MAX_BYTES + 1, "a".repeat(64)))
                .isInstanceOf(VoiceMediaException.class).hasMessage("INVALID_VOICE_MEDIA");
    }

    @Test void createsPrivateUploadForConversationMember() {
        UUID user = UUID.randomUUID();
        when(chats.canAccessConversation(user, "conv_1")).thenReturn(true);
        when(storage.signUpload(anyString(), anyString(), anyString(), any())).thenReturn(
                new VoiceObjectStorage.SignedUpload(URI.create("https://oss.example/upload"), Map.of("Content-Type", "audio/mp4"), Instant.now().plusSeconds(60)));
        var result = service.createUpload(user, "conv_1", "audio/mp4", 128, "b".repeat(64));
        assertThat(result.uploadUrl()).isEqualTo("https://oss.example/upload");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test void rejectsWrongContentTypeAndDigest() {
        UUID user = UUID.randomUUID();
        when(chats.canAccessConversation(user, "conv_1")).thenReturn(true);

        assertThatThrownBy(() -> service.createUpload(
                user, "conv_1", "audio/mpeg", 128, "a".repeat(64)))
                .isInstanceOf(VoiceMediaException.class).hasMessage("INVALID_VOICE_MEDIA");
        assertThatThrownBy(() -> service.createUpload(
                user, "conv_1", "audio/mp4", 128, "not-a-sha256"))
                .isInstanceOf(VoiceMediaException.class).hasMessage("INVALID_VOICE_MEDIA");
    }

    @SuppressWarnings("unchecked")
    @Test void completesUploadOnlyAfterObjectMetadataMatches() {
        UUID user = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String digest = "c".repeat(64);
        var media = new VoiceMediaService.Media(mediaId, user, "conv_1",
                "chat/voice/key.m4a", "audio/mp4", digest, 128, null, "PENDING");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(media));
        when(storage.head("chat/voice/key.m4a"))
                .thenReturn(new VoiceObjectStorage.StoredObject(128, "audio/mp4", digest));

        var completed = service.complete(user, mediaId, 2_000);

        assertThat(completed.status()).isEqualTo("READY");
        assertThat(completed.durationMs()).isEqualTo(2_000);
        verify(jdbc).update(contains("status = 'READY'"), any(Object[].class));
    }
}
