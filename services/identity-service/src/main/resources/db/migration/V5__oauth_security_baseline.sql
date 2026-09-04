ALTER TABLE login_audit
  ADD COLUMN event_type VARCHAR(16) NOT NULL DEFAULT 'LOGIN' AFTER audit_id,
  ADD KEY idx_login_audit_event_occurred_at (event_type, occurred_at);

CREATE TABLE oauth2_refresh_token_family (
  authorization_id VARCHAR(100) NOT NULL,
  status VARCHAR(16) NOT NULL,
  revoke_reason VARCHAR(32) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (authorization_id),
  KEY idx_refresh_token_family_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing authorizations may predate digest-only persistence. They cannot be
-- migrated without temporarily reconstructing credentials, so revoke them.
DELETE FROM oauth2_refresh_token_history;
DELETE FROM oauth2_authorization;
