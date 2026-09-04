CREATE TABLE admin_action_audit (
  audit_id BINARY(16) NOT NULL,
  actor_user_id BINARY(16) NULL,
  action_code VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  result_code VARCHAR(32) NOT NULL,
  reason VARCHAR(200) NULL,
  request_id VARCHAR(128) NOT NULL,
  client_ip_digest BINARY(32) NULL,
  user_agent_digest BINARY(32) NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (audit_id),
  KEY idx_admin_audit_occurred (occurred_at),
  KEY idx_admin_audit_actor (actor_user_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_idempotency (
  actor_user_id BINARY(16) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  action_code VARCHAR(64) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (actor_user_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
