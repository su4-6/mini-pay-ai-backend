ALTER TABLE user_profile
  ADD COLUMN minipay_no VARCHAR(32) NULL AFTER login_name;

UPDATE user_profile
SET minipay_no = CONCAT('MP', UPPER(LEFT(HEX(user_id), 20)))
WHERE minipay_no IS NULL;

ALTER TABLE user_profile
  MODIFY COLUMN minipay_no VARCHAR(32) NOT NULL,
  ADD UNIQUE KEY uk_user_profile_minipay_no (minipay_no);

CREATE TABLE avatar_upload (
  upload_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  expected_content_type VARCHAR(64) NOT NULL,
  expected_size BIGINT NOT NULL,
  expected_sha256 CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  cleanup_attempts INT NOT NULL DEFAULT 0,
  next_cleanup_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (upload_id),
  UNIQUE KEY uk_avatar_upload_object_key (object_key),
  KEY idx_avatar_upload_owner (user_id, created_at),
  KEY idx_avatar_upload_cleanup (status, next_cleanup_at),
  CONSTRAINT chk_avatar_upload_status CHECK (
    status IN ('PENDING', 'CONSUMED', 'REJECTED', 'EXPIRED', 'DELETE_PENDING', 'DELETED')
  ),
  CONSTRAINT chk_avatar_upload_size CHECK (expected_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
