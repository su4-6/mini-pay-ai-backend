CREATE TABLE agent_user_run_gate (
    user_id BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_agent_run_user_status_time
    ON agent_run (user_id, status, updated_at, conversation_id);
