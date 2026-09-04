CREATE TABLE chat_media (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    media_kind VARCHAR(16) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width_px INT NULL,
    height_px INT NULL,
    duration_ms INT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_media_object_key (object_key),
    KEY idx_chat_media_owner_created (owner_user_id, created_at),
    KEY idx_chat_media_conversation_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE chat_message
    ADD COLUMN media_id BINARY(16) NULL AFTER voice_duration_ms,
    ADD COLUMN media_kind VARCHAR(16) NULL AFTER media_id,
    ADD COLUMN media_content_type VARCHAR(64) NULL AFTER media_kind,
    ADD COLUMN media_width_px INT NULL AFTER media_content_type,
    ADD COLUMN media_height_px INT NULL AFTER media_width_px,
    ADD COLUMN media_duration_ms INT NULL AFTER media_height_px,
    ADD KEY idx_chat_message_media (media_id);
