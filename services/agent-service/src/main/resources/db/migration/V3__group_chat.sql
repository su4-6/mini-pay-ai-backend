CREATE TABLE chat_group (
    id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_group_member (
    group_id VARCHAR(64) NOT NULL,
    user_id BINARY(16) NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (group_id, user_id),
    INDEX idx_chat_group_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
