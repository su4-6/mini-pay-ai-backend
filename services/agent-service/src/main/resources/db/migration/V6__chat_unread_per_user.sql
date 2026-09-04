CREATE TABLE chat_conversation_unread (
    conversation_id VARCHAR(64) NOT NULL,
    user_id BINARY(16) NOT NULL,
    unread_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (conversation_id, user_id),
    INDEX idx_chat_conversation_unread_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO chat_conversation_unread (
    conversation_id, user_id, unread_count, created_at, updated_at
)
SELECT id, user_id, 0, created_at, updated_at
FROM chat_conversation;

INSERT IGNORE INTO chat_conversation_unread (
    conversation_id, user_id, unread_count, created_at, updated_at
)
SELECT id, UNHEX(REPLACE(contact_id, '-', '')), 0, created_at, updated_at
FROM chat_conversation
WHERE contact_id REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';

INSERT IGNORE INTO chat_conversation_unread (
    conversation_id, user_id, unread_count, created_at, updated_at
)
SELECT group_id, user_id, 0, joined_at, joined_at
FROM chat_group_member;
