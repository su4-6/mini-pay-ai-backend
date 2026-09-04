package com.minipay.agent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.agent.domain.model.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ChatUnreadIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static ChatRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        repository = new ChatRepository(new JdbcTemplate(dataSource));
    }

    @Test
    void tracksDirectUnreadCountsPerParticipant() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String conversationId = "conv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        repository.ensureConversationExists(first, conversationId, second.toString(), "Friend", 1);

        repository.insertMessage(message(conversationId, first, "first"));

        assertThat(unread(first, conversationId)).isZero();
        assertThat(unread(second, conversationId)).isOne();

        repository.insertMessage(message(conversationId, second, "reply"));

        assertThat(unread(first, conversationId)).isOne();
        assertThat(unread(second, conversationId)).isOne();

        repository.clearUnread(second, conversationId);

        assertThat(unread(first, conversationId)).isOne();
        assertThat(unread(second, conversationId)).isZero();
    }

    @Test
    void tracksGroupUnreadCountsForEveryOtherActiveMember() {
        UUID owner = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        var group = repository.createGroup(owner, List.of(
                new ChatRepository.GroupMember(second, null, "Second"),
                new ChatRepository.GroupMember(third, null, "Third")), "Group");

        repository.insertMessage(message(group.id(), second, "hello"));

        assertThat(unread(owner, group.id())).isOne();
        assertThat(unread(second, group.id())).isZero();
        assertThat(unread(third, group.id())).isOne();

        repository.clearUnread(third, group.id());

        assertThat(unread(owner, group.id())).isOne();
        assertThat(unread(third, group.id())).isZero();

        assertThat(repository.addGroupMembers(owner, group.id(), List.of(
                new ChatRepository.GroupMember(fourth, null, "Fourth")))).isTrue();
        repository.insertMessage(message(group.id(), owner, "welcome"));
        assertThat(unread(owner, group.id())).isOne();
        assertThat(unread(fourth, group.id())).isOne();

        assertThat(repository.removeGroupMember(owner, group.id(), third)).isTrue();
        assertThat(repository.findGroupConversations(third)).isEmpty();
    }

    @Test
    void deletedConversationStaysHiddenUntilANewerMessageAndOldHistoryRemainsPrivate() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String conversationId = "conv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        repository.ensureConversationExists(first, conversationId, second.toString(), "Friend", 1);
        repository.insertMessage(message(conversationId, second, "old"));

        repository.deleteConversationForUser(first, conversationId);

        assertThat(repository.findConversations(first)).isEmpty();
        assertThat(repository.findConversations(second)).hasSize(1);
        assertThat(repository.findMessages(first, conversationId, 50, 0)).isEmpty();
        assertThat(repository.findMessages(second, conversationId, 50, 0)).hasSize(1);

        repository.insertMessage(message(conversationId, second, "new"));

        assertThat(repository.findConversations(first)).hasSize(1);
        assertThat(repository.findMessages(first, conversationId, 50, 0))
                .extracting(ChatMessage::content)
                .containsExactly("new");
        assertThat(repository.countMessages(first, conversationId)).isOne();
        assertThat(repository.findMessages(second, conversationId, 50, 0))
                .extracting(ChatMessage::content)
                .containsExactly("old", "new");
    }

    private static int unread(UUID userId, String conversationId) {
        return java.util.stream.Stream.concat(
                        repository.findConversations(userId).stream(),
                        repository.findGroupConversations(userId).stream())
                .filter(conversation -> conversation.id().equals(conversationId))
                .findFirst()
                .orElseThrow()
                .unreadCount();
    }

    private static ChatMessage message(String conversationId, UUID senderId, String content) {
        return new ChatMessage(null, conversationId, senderId, "Me", content,
                "Text", null, null, null, Instant.now());
    }
}
