-- 已废弃：请不要执行本文件中的历史手工数据。
--
-- 原因：旧脚本包含已移除的 merchant_credential 与旧版入驻字段，
-- 会破坏“密码归 Identity、一个账号多商户”的 V5 基线。
-- 当前本地演示数据由 Flyway 的 demo-auth,demo-data Profile 自动加载：
--   identity-service: R__demo_platform_admin.sql / R__demo_merchant_accounts.sql
--   payment-service:  R__demo_ops_dashboard_and_merchants.sql
-- 账号、联调步骤和数据库安全重建方式见：
--   docs/merchant-end-to-end-acceptance.md
--
-- 下方历史内容保留仅供审计，禁止在任何数据库执行。
/*
-- Local Docker debug data only. Execute after both additive-upgrade SQL files.
-- It adds two clearly labelled test identities and one test merchant; it does not update or delete existing records.
-- Password for both test identities and the merchant credential: MiniPay@123456

USE minipay_identity;

-- Merchant owner: mobile 13900000001, supports merchant password login and SMS login.
INSERT IGNORE INTO user_profile (
  user_id, login_name, minipay_no, phone_hash, nickname, avatar_object_key,
  status, onboarding_status, onboarding_completed_at, version, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
  'local-merchant-owner', 'MPLOCALMERCHANT000001',
  UNHEX('a870acdbfcb389a58fc78851e107b10dca37c7d54c9148484220d8fce0a1f87c'),
  '本地商户验证账号', NULL, 'ACTIVE', 'COMPLETED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
INSERT IGNORE INTO user_credential (
  credential_id, user_id, credential_type, password_hash, status,
  failed_attempts, locked_until, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000122', '-', '')),
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
  'LOGIN_PASSWORD',
  '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
  'ACTIVE', 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
INSERT IGNORE INTO user_role (user_id, role_code, created_at) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')), 'merchant_admin', UTC_TIMESTAMP(6));

-- Separate account for testing "SMS login -> merchant onboarding -> operations review".
INSERT IGNORE INTO user_profile (
  user_id, login_name, minipay_no, phone_hash, nickname, avatar_object_key,
  status, onboarding_status, onboarding_completed_at, version, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')),
  'local-onboarding-applicant', 'MPLOCALAPPLICANT00001',
  UNHEX('852e87fea00a4994dfc9e0900d3753168f2fa8276edf27b1b95f0a8effa57544'),
  '本地入驻验证账号', NULL, 'ACTIVE', 'COMPLETED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
INSERT IGNORE INTO user_role (user_id, role_code, created_at) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')), 'merchant_applicant', UTC_TIMESTAMP(6));

-- Seeded completed users must enter the same wallet-opening event flow as users
-- completing onboarding through Identity. Fixed event ids keep this script idempotent.
INSERT IGNORE INTO outbox_event (
  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
  trace_id, payload_version, payload, status, attempts, next_attempt_at, created_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000211', '-', '')),
   'identity.user.opened', 'user', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
   UTC_TIMESTAMP(6), 'local-seed-merchant-owner', 1,
   JSON_OBJECT('userId', '019fb3d0-0000-7000-8000-000000000121'),
   'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000212', '-', '')),
   'identity.user.opened', 'user', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')),
   UTC_TIMESTAMP(6), 'local-seed-onboarding-applicant', 1,
   JSON_OBJECT('userId', '019fb3d0-0000-7000-8000-000000000111'),
   'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

USE minipay_payment;

-- Active merchant deliberately has no application or collection code yet: first login verifies initialization.
INSERT IGNORE INTO merchant (
  merchant_id, merchant_no, owner_user_id, name, category, contact_name, contact_mobile,
  source, onboarding_id, agreement_version, agreed_at, status, receive_locked, remark,
  default_application_id, version, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000301', '-', '')),
  'MLOCAL20260806001',
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
  '本地验证咖啡店', '其他', '本地验证商户', '13900000001',
  'LOCAL_DEBUG_SEED', NULL, 'v4.7.0', UTC_TIMESTAMP(6), 'ACTIVE', FALSE,
  '仅本地 Docker 验收使用；可由本地清理 SQL 删除。', NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
INSERT IGNORE INTO merchant_credential (
  credential_id, merchant_id, login_account, password_hash, failed_attempts, locked_until,
  invalidated_at, invalidated_reason, last_changed_at, version, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000302', '-', '')),
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000301', '-', '')),
  '019fb3d0-0000-7000-8000-000000000121',
  '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
  0, NULL, NULL, NULL, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
*/
