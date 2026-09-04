CREATE TABLE chat_conversation (
    id VARCHAR(64) NOT NULL,
    user_id BINARY(16) NOT NULL,
    contact_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    last_message VARCHAR(1024) NOT NULL DEFAULT '',
    last_message_time BIGINT NOT NULL,
    unread_count INT NOT NULL DEFAULT 0,
    is_transfer TINYINT(1) NOT NULL DEFAULT 0,
    avatar_color_index INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_chat_conv_user (user_id),
    INDEX idx_chat_conv_user_time (user_id, last_message_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    sender_id BINARY(16) NOT NULL,
    sender_type VARCHAR(16) NOT NULL,
    content VARCHAR(4096) NOT NULL DEFAULT '',
    message_type VARCHAR(16) NOT NULL DEFAULT 'Text',
    transfer_amount VARCHAR(32) NULL,
    transfer_status VARCHAR(32) NULL,
    transfer_direction VARCHAR(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_chat_msg_conv_time (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
