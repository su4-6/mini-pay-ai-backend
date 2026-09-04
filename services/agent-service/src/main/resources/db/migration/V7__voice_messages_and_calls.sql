ALTER TABLE chat_message
    ADD COLUMN voice_media_id BINARY(16) NULL,
    ADD COLUMN voice_duration_ms INT NULL,
    ADD COLUMN call_id BINARY(16) NULL,
    ADD COLUMN call_status VARCHAR(24) NULL,
    ADD COLUMN call_duration_seconds INT NULL;

CREATE TABLE chat_voice_media (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    duration_ms INT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_voice_object_key (object_key),
    INDEX idx_chat_voice_owner_created (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE voice_call (
    id BINARY(16) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    caller_id BINARY(16) NOT NULL,
    callee_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    answered_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_voice_call_caller_status (caller_id, status),
    INDEX idx_voice_call_callee_status (callee_id, status),
    INDEX idx_voice_call_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
