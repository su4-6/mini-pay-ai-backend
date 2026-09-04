INSERT IGNORE INTO user_profile (
  user_id, login_name, minipay_no, phone_hash, nickname, status,
  onboarding_status, onboarding_completed_at, version, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000001', '-', '')),
  'ops-admin-demo',
  'MPOPSADMINDEMO0001',
  UNHEX('0a5a626a3991fbb1ed20780c4d12fb3640e5d0255e68b2bc078bdf9631ed7756'),
  '演示管理员',
  'ACTIVE',
  'COMPLETED',
  UTC_TIMESTAMP(6),
  0,
  UTC_TIMESTAMP(6),
  UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO user_credential (
  credential_id, user_id, credential_type, password_hash, status,
  failed_attempts, locked_until, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000002', '-', '')),
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000001', '-', '')),
  'LOGIN_PASSWORD',
  '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
  'ACTIVE',
  0,
  NULL,
  UTC_TIMESTAMP(6),
  UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO user_role (user_id, role_code, created_at)
VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000001', '-', '')),
  'platform_admin',
  UTC_TIMESTAMP(6)
);
