ALTER TABLE chat_message
    ADD COLUMN transfer_id BINARY(16) NULL AFTER transfer_direction,
    ADD COLUMN transfer_target_user_id BINARY(16) NULL AFTER transfer_id,
    ADD UNIQUE KEY uk_chat_message_conversation_transfer (conversation_id, transfer_id);

ALTER TABLE chat_group
    ADD COLUMN avatar_object_key VARCHAR(512) NULL AFTER owner_id,
    ADD COLUMN avatar_updated_at DATETIME(6) NULL AFTER avatar_object_key;

CREATE TABLE chat_group_avatar_upload (
    id BINARY(16) NOT NULL,
    group_id VARCHAR(64) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_avatar_object_key (object_key),
    KEY idx_group_avatar_group_created (group_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
