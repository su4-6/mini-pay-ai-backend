CREATE TABLE ai_conversation (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    title VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    next_message_sequence BIGINT NOT NULL DEFAULT 1,
    last_message_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_ai_conversation_user_time (user_id, deleted_at, last_message_at DESC, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_run (
    id BINARY(16) NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    client_message_id BINARY(16) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    context_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    intent_type VARCHAR(64) NULL,
    business_ref_type VARCHAR(64) NULL,
    business_ref_id VARCHAR(128) NULL,
    model_provider VARCHAR(64) NULL,
    model_name VARCHAR(128) NULL,
    prompt_version VARCHAR(32) NOT NULL,
    tool_catalog_version VARCHAR(32) NOT NULL,
    next_event_sequence BIGINT NOT NULL DEFAULT 1,
    cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_idempotency (user_id, conversation_id, idempotency_key_hash),
    UNIQUE KEY uk_agent_run_client_message (user_id, client_message_id),
    KEY idx_agent_run_conversation_time (conversation_id, created_at DESC),
    KEY idx_agent_run_status_time (status, updated_at),
    CONSTRAINT fk_agent_run_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_message (
    id BINARY(16) NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    run_id BINARY(16) NULL,
    role VARCHAR(16) NOT NULL,
    content_text VARCHAR(4096) NOT NULL DEFAULT '',
    card_type VARCHAR(64) NULL,
    card_version INT NULL,
    card_payload JSON NULL,
    sequence_no BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_message_sequence (conversation_id, sequence_no),
    KEY idx_ai_message_run (run_id),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id),
    CONSTRAINT fk_ai_message_run FOREIGN KEY (run_id) REFERENCES agent_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_run_event (
    id BINARY(16) NOT NULL,
    run_id BINARY(16) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_version INT NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_event_sequence (run_id, sequence_no),
    KEY idx_agent_run_event_expiry (expires_at),
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_task_state (
    run_id BINARY(16) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    slots_json JSON NOT NULL,
    selected_resource_refs JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT fk_agent_task_state_run FOREIGN KEY (run_id) REFERENCES agent_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_tool_trace (
    id BINARY(16) NOT NULL,
    run_id BINARY(16) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    risk_level VARCHAR(8) NOT NULL,
    schema_version INT NOT NULL,
    request_digest BINARY(32) NOT NULL,
    result_digest BINARY(32) NULL,
    result_code VARCHAR(64) NULL,
    latency_ms BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_agent_tool_trace_run_time (run_id, created_at),
    CONSTRAINT fk_agent_tool_trace_run FOREIGN KEY (run_id) REFERENCES agent_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_setting (
    user_id BINARY(16) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    food_preference_enabled TINYINT(1) NOT NULL DEFAULT 0,
    allergen_avoidance_enabled TINYINT(1) NOT NULL DEFAULT 0,
    meal_budget_enabled TINYINT(1) NOT NULL DEFAULT 0,
    contact_alias_enabled TINYINT(1) NOT NULL DEFAULT 0,
    address_alias_enabled TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memory_item (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    display_value VARCHAR(256) NOT NULL,
    reference_type VARCHAR(32) NULL,
    reference_id VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL,
    consent_message_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_memory_item_user_type (user_id, memory_type, status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contact_relation (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    contact_user_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_relation_owner_contact (owner_user_id, contact_user_id),
    KEY idx_contact_relation_owner_status (owner_user_id, status, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contact_alias (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    contact_relation_id BINARY(16) NOT NULL,
    alias VARCHAR(64) NOT NULL,
    normalized_alias VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_alias_owner_value (owner_user_id, normalized_alias, contact_relation_id),
    KEY idx_contact_alias_relation (contact_relation_id),
    CONSTRAINT fk_contact_alias_relation FOREIGN KEY (contact_relation_id) REFERENCES contact_relation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
