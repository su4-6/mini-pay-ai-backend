CREATE TABLE friend_request (
  id BINARY(16) NOT NULL,
  from_user_id BINARY(16) NOT NULL,
  to_user_id BINARY(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_friend_request (from_user_id, to_user_id, status),
  INDEX idx_friend_request_to (to_user_id, status),
  INDEX idx_friend_request_from (from_user_id, status),
  CONSTRAINT chk_friend_request_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
