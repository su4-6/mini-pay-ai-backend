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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ChatMediaServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatRepository chats = mock(ChatRepository.class);
    private final VoiceObjectStorage storage = mock(VoiceObjectStorage.class);
    private final ChatMediaService service = new ChatMediaService(
            jdbc, chats, storage, Duration.ofMinutes(10), Duration.ofMinutes(5));

    @Test
    void acceptsOnlyNormalizedChatImageUploads() {
        UUID owner = UUID.randomUUID();
        when(chats.canAccessConversation(owner, "conv_1")).thenReturn(true);
        when(storage.signUpload(anyString(), anyString(), anyString(), any())).thenReturn(
                new VoiceObjectStorage.SignedUpload(URI.create("https://oss.example/upload"),
                        Map.of("Content-Type", "image/jpeg"), Instant.now().plusSeconds(60)));

        var upload = service.createUpload(owner, "conv_1", "Image", "image/jpeg", 1024, "a".repeat(64));

        assertThat(upload.uploadUrl()).isEqualTo("https://oss.example/upload");
        verify(jdbc).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> service.createUpload(owner, "conv_1", "Image", "image/png", 1024, "a".repeat(64)))
                .isInstanceOf(VoiceMediaException.class).hasMessage("INVALID_CHAT_MEDIA");
    }

    @Test
    void rejectsOversizedVideoBeforeSigningUpload() {
        UUID owner = UUID.randomUUID();
        when(chats.canAccessConversation(owner, "conv_1")).thenReturn(true);

        assertThatThrownBy(() -> service.createUpload(owner, "conv_1", "Video", "video/mp4",
                ChatMediaService.MAX_VIDEO_BYTES + 1, "b".repeat(64)))
                .isInstanceOf(VoiceMediaException.class).hasMessage("INVALID_CHAT_MEDIA");
    }

    @SuppressWarnings("unchecked")
    @Test
    void completesVideoOnlyWhenStoredMetadataMatches() {
        UUID owner = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String digest = "c".repeat(64);
        var media = new ChatMediaService.Media(mediaId, owner, "group_1", "Video", "chat/media/video.mp4",
                "video/mp4", digest, 2048, null, null, null, "PENDING");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(media));
        when(chats.canAccessConversation(owner, "group_1")).thenReturn(true);
        when(storage.head(media.objectKey())).thenReturn(new VoiceObjectStorage.StoredObject(2048, "video/mp4", digest));

        var completed = service.complete(owner, mediaId, 1280, 720, 60_000);

        assertThat(completed.status()).isEqualTo("READY");
        assertThat(completed.durationMs()).isEqualTo(60_000);
        verify(jdbc).update(contains("status = 'READY'"), any(Object[].class));
        assertThatThrownBy(() -> service.complete(owner, mediaId, 1280, 720, 60_001))
                .isInstanceOf(VoiceMediaException.class).hasMessage("INVALID_CHAT_VIDEO_DURATION");
    }

    @SuppressWarnings("unchecked")
    @Test
    void playbackRequiresCurrentConversationMembership() {
        UUID owner = UUID.randomUUID();
        UUID formerMember = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        var media = new ChatMediaService.Media(mediaId, owner, "group_1", "Image", "chat/media/image.jpg",
                "image/jpeg", "d".repeat(64), 1024, 800, 600, null, "READY");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(media));
        when(chats.canAccessConversation(formerMember, "group_1")).thenReturn(false);

        assertThatThrownBy(() -> service.playback(formerMember, mediaId))
                .isInstanceOf(VoiceMediaException.class).hasMessage("CHAT_MEDIA_FORBIDDEN");
    }
}
