CREATE TABLE user_role (
  user_id BINARY(16) NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id, role_code),
  KEY idx_user_role_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE login_audit (
  audit_id BINARY(16) NOT NULL,
  user_id BINARY(16) NULL,
  login_identifier_hash BINARY(32) NOT NULL,
  authentication_method VARCHAR(32) NOT NULL,
  result_code VARCHAR(32) NOT NULL,
  client_address_hash BINARY(32) NOT NULL,
  user_agent_hash BINARY(32) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (audit_id),
  KEY idx_login_audit_occurred_at (occurred_at),
  KEY idx_login_audit_user_occurred_at (user_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE oauth2_registered_client (
  id VARCHAR(100) NOT NULL,
  client_id VARCHAR(100) NOT NULL,
  client_id_issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  client_secret VARCHAR(200) DEFAULT NULL,
  client_secret_expires_at TIMESTAMP NULL,
  client_name VARCHAR(200) NOT NULL,
  client_authentication_methods VARCHAR(1000) NOT NULL,
  authorization_grant_types VARCHAR(1000) NOT NULL,
  redirect_uris VARCHAR(1000) DEFAULT NULL,
  post_logout_redirect_uris VARCHAR(1000) DEFAULT NULL,
  scopes VARCHAR(1000) NOT NULL,
  client_settings VARCHAR(2000) NOT NULL,
  token_settings VARCHAR(2000) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_oauth2_registered_client_client_id (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE oauth2_authorization (
  id VARCHAR(100) NOT NULL,
  registered_client_id VARCHAR(100) NOT NULL,
  principal_name VARCHAR(200) NOT NULL,
  authorization_grant_type VARCHAR(100) NOT NULL,
  authorized_scopes VARCHAR(1000) DEFAULT NULL,
  attributes BLOB DEFAULT NULL,
  state VARCHAR(500) DEFAULT NULL,
  authorization_code_value BLOB DEFAULT NULL,
  authorization_code_issued_at TIMESTAMP NULL,
  authorization_code_expires_at TIMESTAMP NULL,
  authorization_code_metadata BLOB DEFAULT NULL,
  access_token_value BLOB DEFAULT NULL,
  access_token_issued_at TIMESTAMP NULL,
  access_token_expires_at TIMESTAMP NULL,
  access_token_metadata BLOB DEFAULT NULL,
  access_token_type VARCHAR(100) DEFAULT NULL,
  access_token_scopes VARCHAR(1000) DEFAULT NULL,
  oidc_id_token_value BLOB DEFAULT NULL,
  oidc_id_token_issued_at TIMESTAMP NULL,
  oidc_id_token_expires_at TIMESTAMP NULL,
  oidc_id_token_metadata BLOB DEFAULT NULL,
  refresh_token_value BLOB DEFAULT NULL,
  refresh_token_issued_at TIMESTAMP NULL,
  refresh_token_expires_at TIMESTAMP NULL,
  refresh_token_metadata BLOB DEFAULT NULL,
  user_code_value BLOB DEFAULT NULL,
  user_code_issued_at TIMESTAMP NULL,
  user_code_expires_at TIMESTAMP NULL,
  user_code_metadata BLOB DEFAULT NULL,
  device_code_value BLOB DEFAULT NULL,
  device_code_issued_at TIMESTAMP NULL,
  device_code_expires_at TIMESTAMP NULL,
  device_code_metadata BLOB DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_oauth2_authorization_principal (principal_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE oauth2_authorization_consent (
  registered_client_id VARCHAR(100) NOT NULL,
  principal_name VARCHAR(200) NOT NULL,
  authorities VARCHAR(1000) NOT NULL,
  PRIMARY KEY (registered_client_id, principal_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
