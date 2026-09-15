-- ============================================================================
-- 服务器上补齐演示账号（幂等，可重复执行）
--
-- 背景：线上这台服务器只跑了 Flyway 迁移，仓库里 scripts/k3s/sql/ 那些
-- 「建账号 / 授权」脚本只对本地集群执行过，所以线上缺少商户端与管理端账号。
--
-- 关键点：
--   1. phone_hash = HMAC-SHA256(key=PHONE_HASH_PEPPER, msg=手机号)，大写 hex。
--      下面的哈希是用【本服务器自己的 pepper】算的，已用 ops-admin-demo 校验通过。
--   2. 密码统一复用演示口令 MiniPay@123456 的 argon2id 字面量。
--   3. 角色存在 user_role.role_code（字符串），不是 role_id。
--   4. 管理端账号只给 system_super_admin，【不能】同时给 platform_admin ——
--      否则 admin-web 的角色判断会把运营端登录弹到管理端（见 fix-system-admin-role.sql 注释）。
-- ============================================================================

-- 1) 商户端：把 13900000009 绑到已有的 merchant-owner-demo 上
UPDATE minipay_identity.user_profile
SET phone_hash   = UNHEX('72f89bf2816a757b9f10dbdcecae95b023a32a6d9f8b2523c0c390fb5e560f69'),
    phone_masked = '139****0009',
    status       = 'ACTIVE',
    updated_at   = UTC_TIMESTAMP(6)
WHERE login_name = 'merchant-owner-demo';

UPDATE minipay_identity.user_credential c
JOIN minipay_identity.user_profile u ON u.user_id = c.user_id
SET c.password_hash   = '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
    c.status          = 'ACTIVE',
    c.failed_attempts = 0,
    c.locked_until    = NULL,
    c.updated_at      = UTC_TIMESTAMP(6)
WHERE u.login_name = 'merchant-owner-demo';

INSERT IGNORE INTO minipay_identity.user_role (user_id, role_code, created_at)
SELECT user_id, 'merchant_admin', UTC_TIMESTAMP(6)
FROM minipay_identity.user_profile WHERE login_name = 'merchant-owner-demo';

-- 2) 管理端：新建 13800138002（系统管理员）
INSERT INTO minipay_identity.user_profile
  (user_id, login_name, minipay_no, phone_hash, phone_masked, nickname, status,
   onboarding_status, onboarding_completed_at, version, created_at, updated_at)
VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000003','-','')),
   'sys-admin-demo', 'MPSYSADMINDEMO001',
   UNHEX('e85affb556a793f8a6851f9a1ee2e359e3e11696522a4534ad3dccc762639878'),
   '138****8002',
   CONVERT(UNHEX('e7b3bbe7bb9fe7aea1e79086e59198') USING utf8mb4),
   'ACTIVE', 'COMPLETED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  phone_hash   = VALUES(phone_hash),
  phone_masked = VALUES(phone_masked),
  status       = 'ACTIVE',
  updated_at   = UTC_TIMESTAMP(6);

INSERT INTO minipay_identity.user_credential
  (credential_id, user_id, credential_type, password_hash, status,
   failed_attempts, locked_until, created_at, updated_at)
VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000004','-','')),
   UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000003','-','')),
   'LOGIN_PASSWORD',
   '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
   'ACTIVE', 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  password_hash   = VALUES(password_hash),
  status          = 'ACTIVE',
  failed_attempts = 0,
  locked_until    = NULL,
  updated_at      = UTC_TIMESTAMP(6);

INSERT IGNORE INTO minipay_identity.user_role (user_id, role_code, created_at)
SELECT user_id, 'system_super_admin', UTC_TIMESTAMP(6)
FROM minipay_identity.user_profile WHERE login_name = 'sys-admin-demo';

-- 3) 运营端：确保 ops-admin-demo 只有 platform_admin（不能被弹到管理端）
INSERT IGNORE INTO minipay_identity.user_role (user_id, role_code, created_at)
SELECT user_id, 'platform_admin', UTC_TIMESTAMP(6)
FROM minipay_identity.user_profile WHERE login_name = 'ops-admin-demo';

DELETE FROM minipay_identity.user_role
WHERE role_code IN ('system_super_admin','system_account_admin')
  AND user_id = (SELECT user_id FROM minipay_identity.user_profile WHERE login_name='ops-admin-demo');

-- 4) 顺带补上两个没有凭据的种子账号（consumer / applicant），便于 App 侧演示
INSERT INTO minipay_identity.user_credential
  (credential_id, user_id, credential_type, password_hash, status,
   failed_attempts, locked_until, created_at, updated_at)
SELECT UNHEX(REPLACE(CONCAT('019fb3d0-0000-7000-8000-0000000001',
                LPAD(HEX(CRC32(u.login_name)), 2, '0')),'-','')),
       u.user_id, u.credential_type,
       '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
       'ACTIVE', 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM minipay_identity.user_profile u
WHERE u.login_name IN ('consumer-payer-demo','merchant-applicant-demo')
  AND NOT EXISTS (SELECT 1 FROM minipay_identity.user_credential c WHERE c.user_id = u.user_id);
