CREATE TABLE oauth2_refresh_token_history (
  token_digest VARCHAR(96) NOT NULL,
  authorization_id VARCHAR(100) NOT NULL,
  active BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  PRIMARY KEY (token_digest),
  KEY idx_refresh_history_authorization (authorization_id),
  KEY idx_refresh_history_reuse (active, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
