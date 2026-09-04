# 本地 Docker 联调与验收手册

适用范围：运营端 + 商户 B 端 + Identity + Payment + Wallet。C 端不在本轮验收范围内。

## 1. 数据库整理结果与执行顺序

所有 SQL 都是新文件；不会覆盖 `services/*/db/migration` 下的任何既有文件。

| 顺序 | 新文件 | 作用 |
|---:|---|---|
| 1 | `docs/sql/minipay_docker_local_cleanup.sql` | 仅删除过期的 IDEA 隔离库和 smoke 库，保留标准库及其数据 |
| 2 | `docs/sql/minipay_identity_legacy_additive_upgrade.sql` | 补齐旧 Identity 库对当前商户短信/密码登录所需的字段与表 |
| 3 | `docs/sql/minipay_payment_legacy_additive_upgrade.sql` | 补齐旧 Payment 库的商户 B 端结构 |
| 4 | `docs/sql/minipay_local_debug_seed.sql` | 新增明确标记的本地测试账号和测试商户，不改写已有商户 |

在后端根目录 `C:\Users\hp\Desktop\数字马力\mini-pay-ai-backend` 的 IDEA Terminal 中依次执行：

```powershell
Get-Content docs\sql\minipay_docker_local_cleanup.sql | docker compose exec -T mysql-core mysql -uroot -pminipay-root
Get-Content docs\sql\minipay_identity_legacy_additive_upgrade.sql | docker compose exec -T mysql-core mysql -uroot -pminipay-root
Get-Content docs\sql\minipay_payment_legacy_additive_upgrade.sql | docker compose exec -T mysql-core mysql -uroot -pminipay-root
Get-Content docs\sql\minipay_local_debug_seed.sql | docker compose exec -T mysql-core mysql -uroot -pminipay-root
```

说明：第 2、3 步是对当前旧标准库的**一次性**追加补丁；若已成功执行过，不要重复执行。第 1、4 步可以重复执行；清理脚本只会作用于列出的四个过期库，种子 SQL 使用 `INSERT IGNORE`。

## 2. 启动方式

1. Docker Desktop 启动后，在 IDEA Services 启动 `compose.yaml` 的 `mysql-core`、`redis`、`rabbitmq`、`seata-server`。
2. IDEA 执行 **File → Reload All from Disk**。
3. 选择右上角的 `MiniPay Backend`，运行。当前 IDEA 标准配置连接 `minipay_identity`、`minipay_payment_v15`、`minipay_wallet`；Payment 必须使用 V15 库，不能再切回旧的 `minipay_payment` 或 `*_idea_dev`。不要同时运行自动发现的旧 Spring Boot 配置，以免端口冲突或服务连接不同库。
4. 在 `mini-pay-ai-frontend` 根目录开两个 Terminal：

```powershell
pnpm.cmd --filter @minipay/ops-web dev -- --port 8000
```

```powershell
pnpm.cmd --filter @minipay/merchant-web dev -- --port 8001
```

访问地址：运营端 `http://localhost:8000`；商户端 `http://localhost:8001`。

## 3. 可用验收账号

| 端 | 手机号 | 密码/验证码 | 用途 |
|---|---|---|---|
| 运营端 | `13800138000` | 密码 `MiniPay@123456` | 源码已有 platform_admin 演示账号；图形验证码按登录页图片输入 |
| 商户端 | `13900000001` | 密码 `MiniPay@123456` | 本地种子商户 `本地验证咖啡店`，用于完整商户工作台验收 |
| 商户端入驻 | `13900000002` | 短信验证码 `123456` | 本地入驻申请、运营审核联调 |

短信 `123456` 的前提是启动 `MiniPay Identity` 时保留 `SMS_PROVIDER=demo` 和 `DEMO_SMS_CODE=123456`（IDEA 配置已包含）。商户端登录现在复用 Identity 的动态图形验证码：每次获取短信或提交密码前，都要按图片输入 4 位验证码；点击图片可刷新，验证码仅一次有效。

## 4. 商户端功能验收

### A. 登录视觉与功能

1. 打开 `http://localhost:8001`。
2. 确认页面与运营端认证页一致：渐变背景、MiniPay 图标、品牌标题、居中 368px 登录区、页脚审计提示。
3. 确认标签为“密码登录 / 验证码登录”，标题为“商户平台”。
4. 使用 `13900000001 / MiniPay@123456`，先按页面图片输入 4 位图形验证码，再在“密码登录”成功进入工作台。
5. 切换“验证码登录”，输入 `13900000001`，按页面图片输入 4 位图形验证码，获取验证码后输入 `123456`，应成功登录。
6. 点击“忘记密码”，短信验证后设置符合规则的新密码（6-20 位、字母和数字），退出后用新密码再次登录。

### B. 首次初始化、收款码和应用

首次登录本地测试商户会自动创建默认自用应用和经营收款码；这是为了覆盖初始化联调。

1. 在“经营收款码”确认默认应用和收款码状态为 `ENABLED`。
2. 停用、启用收款码，确认状态切换成功。
3. 点击“重新生成收款码”，复制新码；确认旧码失效提示出现。
4. 在“应用中心”新建应用，保存首次返回的 App Secret。
5. 编辑应用的通知地址、退款通知地址、IP 白名单、权限、可用渠道并保存。
6. 停用后再启用应用；重置密钥并确认仅显示一次；对无交易的非默认应用执行删除。

### C. 商户资料与看板

1. 在“商户设置”修改类目、联系人和联系电话，保存后刷新页面确认数据仍存在。
2. 在“登录密码”修改密码：先输入当前密码，再输入新密码；使用新密码重新登录。
3. 在“看板”切换近 7 天和近 30 天。测试商户没有伪造订单时金额为 0，这是正常的空数据验证；已有订单/退款记录仍保留在原 `MiniPay Demo Coffee` 商户。

### D. 订单、渠道与个人钱包联调

1. “订单中心”展示订单模块返回的真实订单；点击订单可查看订单号、商户订单号、应用、金额、渠道、状态、时间和脱敏付款方。
2. 符合退款条件的订单显示退款入口；提交后由订单模块处理，商户端不直接修改订单表或钱包余额。
3. 看板“渠道占比”使用订单模块聚合结果；无订单时显示中文空状态，不生成演示流水。
4. “个人钱包”是独立只读模块，显示当前商户 owner 的可用余额、冻结金额、总余额和最近账单；不提供充值、提现、转账、绑卡，也不创建商户钱包。
5. 本地账号 `13900000001` 的个人钱包由 `identity.user.opened` 事件按钱包模块原流程创建，首笔 `OPENING_GRANT` 账单可用于确认跨模块消费成功。

### E. 入驻—运营审核联调

1. 退出商户端，使用 `13900000002` 以短信验证码登录。
2. 因该账号没有商户，系统显示入驻申请；填写商户名称、类目、联系人，勾选协议并提交。
3. 登录运营端，进入商户入驻审核页面，找到该待审核申请；该页面属于平台运营职责，不是商户工作台功能。
4. 先测试拒绝：填写拒绝原因，回商户端确认可修改并重新提交。
5. 再测试通过：回到商户端刷新，确认商户状态变为 ACTIVE，并完成首次初始化。

## 5. 运营端与既有模块回归

1. 使用运营账号登录 `http://localhost:8000`。
2. 在商户管理中确认同时可见原 `MiniPay Demo Coffee` 与本地测试商户。
3. 在待办/入驻审核中查看并处理第 4.D 节创建的申请。
4. 在应用、通知、登录审计等页面确认页面可加载；运营端账号与商户端账号互不混用。
5. 打开 IDEA Database，确认标准库而非隔离库：`minipay_identity`、`minipay_payment`、`minipay_wallet`、`minipay_agent`、`seata`。

## 6. 数据库验收查询

```sql
USE minipay_payment_v15;
SELECT merchant_no, name, status, source
FROM merchant
ORDER BY created_at;

SELECT m.merchant_no, c.login_account, c.failed_attempts, c.invalidated_at
FROM merchant m
LEFT JOIN merchant_credential c ON c.merchant_id = m.merchant_id
ORDER BY m.created_at;

SHOW TABLES LIKE 'merchant%';

USE minipay_wallet;
SELECT installed_rank, version, description, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT COUNT(*) AS wallet_account_count FROM wallet_account;
```

预期：V15 库保留原商户 `M20260803001`，并包含本地验证商户 `MLOCAL20260806001`；V15/V16 Flyway 历史成功，`merchant_collection_code.token_ciphertext` 已存在。Wallet 中应能按 `owner_user_id=019fb3d0-0000-7000-8000-000000000121` 查到事件创建的个人钱包及 `OPENING_GRANT` 账单；不要手工伪造商户钱包。
