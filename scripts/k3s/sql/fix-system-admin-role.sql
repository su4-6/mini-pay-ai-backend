-- 修正上一轮的授权方式：系统管理员角色不该叠加在运营端账号上。
--
-- 背景（实测发现的设计行为）：
--   apps/ops-web/src/components/AuthGate.tsx:75-79
--     const shouldUseAdminPortal = isSystemAdministrator(admin?.roles);
--     if (shouldUseAdminPortal) { window.location.replace(ADMIN_WEB_PUBLIC_URL); }
--   即：系统管理员会被**刻意**挡在运营端之外、强制跳去管理端。
--   因此把 system_super_admin 给运营端账号（13800138000）会导致：
--   一登录就被踢去管理端，而管理端地址是前端构建期常量（当前被烤成 localhost:8002）→ 白屏。
--
-- 正确做法：系统管理员用**独立账号**。
--   本脚本新建 13800138002 / 系统管理员，密码沿用演示统一的 MiniPay@123456
--   （通过复制现有账号的 Argon2id 密码哈希，不重新生成）。
--
-- 手机号哈希算法已实测确认：HMAC-SHA256(key=PHONE_HASH_PEPPER, msg=手机号)
--   13800138000 -> 780D44631C816AD928283218FEF5B58BC3BC29404D94EB15DC46D4068BF19A7B
--   与库中 演示管理员 的 phone_hash 前缀 780d4463 一致。

SET NAMES utf8mb4;

-- 1) 撤销叠加在运营端账号上的系统角色
DELETE r FROM user_role r
  JOIN user_role p ON p.user_id = r.user_id AND p.role_code = 'platform_admin'
 WHERE r.role_code = 'system_super_admin';

-- 2) 新建独立系统管理员账号（克隆已有账号的结构，替换身份字段）
--    13800138002 的 phone_hash 已按上面的算法预先算好。
INSERT INTO user_profile
  (user_id, login_name, minipay_no, phone_hash, phone_masked,
   phone_ciphertext, phone_nonce, phone_key_id, disclosure_version,
   nickname, avatar_object_key, status, credential_type,
   onboarding_status, onboarding_completed_at, version, created_at, updated_at)
SELECT UNHEX('019FDF99000170008000000000000001'),
       'system-admin-demo',
       'MPSYSTEMADMIN0000001',
       UNHEX('762DC14AFE4A3A9606F8A9AA8470DF281DA5D9A33E1FA8F0D76AB1C49E9BCEA7'),
       '138****8002',
       NULL, NULL, NULL, 0,
       '系统管理员',
       NULL, 'ACTIVE', 'PASSWORD',
       'COMPLETED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT user_id FROM user_profile WHERE login_name='system-admin-demo') x);

-- 3) 克隆密码凭据（沿用 MiniPay@123456 的 Argon2id 哈希）
INSERT INTO user_credential
  (credential_id, user_id, credential_type, password_hash, status,
   failed_attempts, locked_until, created_at, updated_at)
SELECT UNHEX('019FDF99000270008000000000000001'),
       UNHEX('019FDF99000170008000000000000001'),
       c.credential_type, c.password_hash, 'ACTIVE',
       0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
  FROM user_credential c
  JOIN user_profile src ON src.user_id = c.user_id
  JOIN user_profile dst ON dst.login_name = 'system-admin-demo'
 WHERE src.login_name = 'ops-admin-demo' OR src.nickname = '演示管理员'
 LIMIT 1;

-- 4) 授予系统管理员角色（仅给新账号）
INSERT IGNORE INTO user_role (user_id, role_code, created_at)
VALUES (UNHEX('019FDF99000170008000000000000001'), 'system_super_admin', UTC_TIMESTAMP(6));

-- 5) 校验
SELECT p.login_name, p.nickname, p.phone_masked, p.credential_type,
       (SELECT COUNT(*) FROM user_credential c WHERE c.user_id = p.user_id) AS creds,
       (SELECT GROUP_CONCAT(r.role_code) FROM user_role r WHERE r.user_id = p.user_id) AS roles
  FROM user_profile p
 WHERE p.login_name IN ('system-admin-demo', 'ops-admin-demo');

SELECT r.role_code, COUNT(*) AS n,
       GROUP_CONCAT(p.nickname ORDER BY p.nickname) AS who
  FROM user_role r JOIN user_profile p ON p.user_id = r.user_id
 GROUP BY r.role_code ORDER BY r.role_code;
