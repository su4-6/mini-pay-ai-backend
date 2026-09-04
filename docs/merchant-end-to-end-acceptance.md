# 商户 B 端全链路联调与验收手册

## 1. 启动基线

使用 JDK 21，后端根目录导入 IDEA Maven 项目。先仅启动基础设施（不要同时启动 Docker 应用容器和 IDEA 服务，避免 8081/8082/8083/8088 端口冲突）：

```powershell
cd C:\Users\hp\Desktop\数字马力\mini-pay-ai-backend
docker compose up -d mysql-core redis rabbitmq seata-server callback-mock
```

IDEA 中依次启动 Identity(8081)、Wallet(8083)、Payment(8082)、Management BFF(8088)。每个配置使用 JDK 21；Identity/Payment 加 `SPRING_PROFILES_ACTIVE=demo-auth,demo-data`，Payment 另加 `MERCHANT_APP_SECRET_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=`。其余数据库地址沿用 `application.yml` 的 localhost:3306。

前端在两个终端分别启动：

```powershell
cd C:\Users\hp\Desktop\数字马力\mini-pay-ai-frontend
$env:PORT=8000; pnpm.cmd --filter @minipay/ops-web dev
```

```powershell
cd C:\Users\hp\Desktop\数字马力\mini-pay-ai-frontend
$env:PORT=8001; pnpm.cmd --filter @minipay/merchant-web dev
```

## 2. 演示账号

| 角色 | 账号 | 凭证 | 用途 |
| --- | --- | --- | --- |
| 运营管理员 | 13800138000 | `MiniPay@123456` | 审批入驻、应用、查看通知与人工重试 |
| 多商户店主 | 13900000001 | `MiniPay@123456` | 登录商户端，验证两个已审核商户及初始化 |
| 待入驻商家 | 13900000002 | 短信码 `123456` | 提交、补件、重提入驻 |
| C 端付款人 | 13900000003 | 短信码 `123456` | 扫经营码、建单、支付与退款验证 |

页面如启用图形验证码，请输入当前图片中的字符；发送短信后无需再次输入图形验证码。演示环境短信验证码固定为 `123456`。

## 3. 必测业务链路

1. 店主登录商户端，切换两个商户，验证订单/看板/应用数据互不串租户。
2. 对每个商户点击“初始化”：第一次生成默认应用和经营码；连续点击或刷新后只保留一套默认资源。
3. 在“应用与密钥”领取一次密钥。刷新后不再显示明文；重置后领取新密钥。为支付和退款分别配置通知地址。
4. 在“经营收款码”验证二维码为随机 token，启停后不能收款；换码后旧二维码解析失败。
5. 待入驻用户登录并提交申请；运营端审核通过后出现 ACTIVE 商户；再验证补件、拒绝和重提。
6. C 端按 API 依次调用扫码解析、建单、支付。确认订单写入 merchant/app/merchantOrderNo，店主个人钱包到账，商户看板出现真实数据。
7. 商户订单页打开详情并发起全额退款。确认付款人到账、店主钱包扣回、看板和退款回调均更新；店主余额不足时退款整体失败。
8. 运营端查看回调失败记录并人工重试。确认计数不归零，重复事件仅保留一条 Inbox 处理记录。

## 4. 回调 Mock 验收

`callback-mock` Docker 端口为 8098。把应用密钥登记给 Mock（应用服务在 Docker 内时用 `callback-mock`；IDEA 本地服务配置 `localhost`）：

```powershell
Invoke-RestMethod -Method Post http://localhost:8098/v1/secrets -ContentType application/json -Body '{"appId":"你的AppId","appSecret":"刚领取的密钥"}'
```

通知地址可分别填写：

```text
http://callback-mock:8098/callbacks/payment/success
http://callback-mock:8098/callbacks/refund/success
```

把路径中的 `success` 改为 `fail` 验证 500，改为 `timeout` 验证超时。查看记录：`GET http://localhost:8098/v1/records`。Mock 校验 HMAC 签名并记录重复通知。

## 5. 数据库核对（IDEA Database）

建立 MySQL 数据源：`jdbc:mysql://localhost:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`，用户名 `root`，密码见 compose 环境变量。勾选 `minipay_identity`、`minipay_payment`、`minipay_wallet`、`minipay_agent`、`seata`。

```sql
USE minipay_payment;
SELECT merchant_no, name, owner_user_id, status, default_application_id FROM merchant;
SELECT app_id, merchant_id, status, notify_url, refund_notify_url FROM merchant_application;
SELECT merchant_id, application_id, merchant_order_no, status, amount FROM payment_order ORDER BY created_at DESC;
SELECT notification_type, status, attempt_count, response_status FROM merchant_notification_delivery ORDER BY created_at DESC;

USE minipay_wallet;
SELECT user_id, available_balance, frozen_balance FROM wallet_account;
SELECT business_type, amount, direction, idempotency_key FROM wallet_bill ORDER BY created_at DESC;
```

库中英文状态码是稳定的内部枚举；前端应显示中文。所有字段、字符集均应为 utf8mb4。

## 6. Docker 数据库安全重建（首次执行前备份）

此步骤会重建 **仅 MySQL** 数据卷；不会删除 Redis、RabbitMQ、Seata 卷。先手动确认 Docker volume 的真实名称，再执行删除命令，禁止使用 `docker compose down -v`。

```powershell
New-Item -ItemType Directory -Force C:\Users\hp\Desktop\MiniPay-local-backup
docker compose exec -T mysql-core mysqldump -uroot -pminipay-root --databases minipay_identity minipay_payment minipay_wallet minipay_agent seata | Out-File -Encoding utf8 C:\Users\hp\Desktop\MiniPay-local-backup\minipay-before-v5.sql
docker compose stop mysql-core
docker volume ls
# 确认名称后，只删除 compose 对应的 mysql 数据卷，例如：
docker volume rm mini-pay-ai-backend_mysql-core-data
docker compose up -d mysql-core
```

MySQL 健康后再启动各服务，Flyway 会创建表结构与 demo 数据。切勿对已有团队共享数据库执行此步骤。
