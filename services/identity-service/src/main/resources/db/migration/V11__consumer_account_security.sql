ALTER TABLE user_profile
  ADD COLUMN phone_masked VARCHAR(32) NULL AFTER phone_hash;

CREATE TABLE consumer_email_contact (
  user_id BINARY(16) NOT NULL,
  email_hmac BINARY(32) NOT NULL,
  email_masked VARCHAR(320) NOT NULL,
  verified_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_consumer_email_contact_hmac (email_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
