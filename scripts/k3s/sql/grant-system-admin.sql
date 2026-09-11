-- 给已存在的管理员账号授予系统后台角色，使自研管理端 admin-web 可以登录。
--
-- 背景：admin-web 的接口要求 system_super_admin / system_account_admin 角色
--       （见 SystemAdminController 的 SYSTEM_ROLES），而环境内 system_* 角色原本为 0，
--       导致该页面无人能进。
--
-- 为什么不用内置引导 BOOTSTRAP_SYSTEM_ADMIN_MOBILE：
--   1. identity-service 的 SystemAdminBootstrap 在「该手机号已被现有账号绑定」时
--      直接抛 IllegalStateException("Bootstrap administrator mobile is already bound")，
--      导致启动失败、Pod CrashLoopBackOff。实测已踩到。
--   2. 它创建的是 credential_type='SMS' 的新账号；在 SMS_PROVIDER=disabled 时无法登录。
--
-- 因此这里改为：给已经具备密码凭据的管理员账号直接补一条角色记录。
-- 幂等：先删后插，可重复执行。

SET NAMES utf8mb4;

-- 演示管理员（13800138000，platform_admin）同时具备系统后台角色。
INSERT IGNORE INTO minipay_identity.user_role (user_id, role_code, created_at)
SELECT user_id, 'system_super_admin', UTC_TIMESTAMP(6)
  FROM minipay_identity.user_role
 WHERE role_code = 'platform_admin';

-- 校验：应看到 system_super_admin 至少 1 人
SELECT r.role_code, COUNT(*) AS accounts, GROUP_CONCAT(p.nickname) AS who
  FROM minipay_identity.user_role r
  JOIN minipay_identity.user_profile p ON p.user_id = r.user_id
 GROUP BY r.role_code
 ORDER BY r.role_code;
