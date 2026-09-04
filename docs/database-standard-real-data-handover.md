# 标准库接入与商户 B 端数据库交付（2026-08-06）

## 交付边界

本次只新增文件和新增数据库对象，未覆盖仓库既有 Flyway 文件，未删除、清空或复制任何已有业务记录。

新增的 Payment 补丁文件：

`docs/sql/minipay_payment_legacy_additive_upgrade.sql`

它用于当前本机已存在、且 Flyway V3 与现源码不一致的 `minipay_payment` 旧库。脚本只做以下事情：

- 为已有 `merchant`、`merchant_application`、`payment_order` 追加商户 B 端运行所需列和索引；
- 创建商户入驻、商户密码凭据、收款码、通知、审计等缺失表；
- 用原 `minipay_app_id`、`name` 回填新增的同义列，不创建商户、应用、订单或退款记录；
- 不包含 `DROP`、`DELETE`、`TRUNCATE`、`UPDATE` 既有业务字段或插入演示商户数据。

标准 `minipay_wallet` 原先不存在。本次由 Wallet 服务**已有的** V1–V6 Flyway 文件从空库创建；没有新写 Wallet SQL，也没有导入虚构用户或流水。

## 实际核验结果

### `minipay_payment`

原记录数量在补丁前后保持一致：

| 数据 | 数量 |
|---|---:|
| merchant | 1 |
| merchant_application | 3 |
| payment_order | 3 |
| refund_order | 1 |

新增商户 B 端表：

`merchant_collection_code`、`merchant_credential`、`merchant_daily_metric`、`merchant_inbox_message`、`merchant_notification`、`merchant_notification_attempt`、`merchant_onboarding`、`merchant_operation_audit`、`merchant_scan_resolution`。

原有商户 `M20260803001 / MiniPay Demo Coffee` 和三个原有应用仍保留。已有应用的原编号和名称被映射到新增同义列；没有生成新应用。

### `minipay_wallet`

Flyway 已完成 V1–V6，当前标准表为：

`wallet_account`、`ledger_entry`、`ledger_transaction`、`wallet_bill`、`pending_credit`、`wallet_posting_idempotency`、`account_freeze`、`inbox_message`、`outbox_event`、`tcc_fence_log`。

迁移后只有源码规定的 3 条基础 `wallet_account` 记录；`ledger_entry` 和 `wallet_bill` 均为 0，未写入测试交易。

## 重要登录说明

已有商户记录没有历史 `merchant_credential`（商户密码哈希）可供安全迁移。因此本次没有伪造密码、也无法凭空给原商户发放“密码登录”账号。

- 已有商户可走商户端的手机号 + 短信验证码流程；本地 demo 短信码由 Identity 启动配置指定为 `123456`。
- 商户密码在真实商户完成入驻、或通过“忘记密码”短信验证设置后才会写入 `merchant_credential`。
- 这是防止用明文或临时密码覆盖真实凭据；若业务方能提供已有密码哈希或正式账号初始化规则，再由其按真实资料导入。

## IDEA：标准库启动配置

为避免一次启动时 Identity、Payment、Wallet 分别连到不同的库，原有的 `MiniPay_*.xml` 已统一改为标准 Docker 库；保留的 `MiniPay Standard *.xml` 只是同一配置的兼容别名：

- `.idea/runConfigurations/MiniPay_Standard_Identity.xml`
- `.idea/runConfigurations/MiniPay_Standard_Payment.xml`
- `.idea/runConfigurations/MiniPay_Standard_Wallet.xml`
- `.idea/runConfigurations/MiniPay_Standard_Management_BFF.xml`
- `.idea/runConfigurations/MiniPay_Standard_Backend.xml`

在 IDEA 执行 **File → Reload All from Disk** 后，右上角选择 `MiniPay Backend`，点击运行即可。它连接的是真实标准库：

| 服务 | 数据库 | Flyway 策略 |
|---|---|---|
| Identity | `minipay_identity` | 关闭，保护旧库当前历史 |
| Payment（含商户 B） | `minipay_payment` | 关闭，使用本次新增补丁 |
| Wallet | `minipay_wallet` | 开启，已处于 V6 |
| Management BFF | 无独立库 | 代理上述服务 |

Identity 和 Payment 关闭 Flyway 是为避免当前源码迁移版本与已有旧库的历史记录发生冲突；这不是修改或绕过数据库结构。商户 B 所需的 Payment 结构已经由独立、可审阅的新增 SQL 完成。

## 可复核 SQL

在 IDEA Database 控制台执行：

```sql
USE minipay_payment;

SELECT 'merchant' AS table_name, COUNT(*) AS row_count FROM merchant
UNION ALL SELECT 'merchant_application', COUNT(*) FROM merchant_application
UNION ALL SELECT 'payment_order', COUNT(*) FROM payment_order
UNION ALL SELECT 'refund_order', COUNT(*) FROM refund_order;

SHOW TABLES LIKE 'merchant%';

USE minipay_wallet;
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```
