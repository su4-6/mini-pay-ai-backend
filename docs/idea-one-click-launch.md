# IDEA 一键启动配置说明

## 已写入的本地 IDEA 文件

| 文件 | 作用 |
|---|---|
| `.idea/misc.xml` | 将项目 Java language level 固定为 JDK 21 |
| `.idea/runConfigurations/MiniPay_Identity.xml` | Identity Spring Boot 配置与本地 demo 环境变量 |
| `.idea/runConfigurations/MiniPay_Wallet.xml` | Wallet Spring Boot 配置 |
| `.idea/runConfigurations/MiniPay_Payment.xml` | Payment（含商户 B 端）Spring Boot 配置与密钥 |
| `.idea/runConfigurations/MiniPay_Management_BFF.xml` | Management BFF 配置与本地服务地址 |
| `.idea/runConfigurations/MiniPay_Backend.xml` | 同时启动上述四个 Spring Boot 服务的 Compound 配置 |

`.idea` 已在 Git ignore 中；这些是你的本机 IDEA 设置，不会混入项目代码提交。

## Docker Compose：只需要在 IDEA 图形界面创建一次

Docker Desktop 的连接标识是本机相关配置，IDEA 会将其写入 `.idea/workspace.xml`，不适合手写或共享。因此请在 IDEA 做以下一次性操作：

1. 打开 **View → Tool Windows → Services**。
2. 点击 `+`，选择 **Docker Connection**，选择 Docker Desktop（Windows named pipe）。
3. 再点击 `+`，选择 **Docker Compose**，选择 `$PROJECT_DIR$/compose.yaml`。
4. 勾选 `mysql-core`、`redis`、`rabbitmq`、`seata-server`，点击 **Up**。
5. 等四个容器状态为 healthy 后，在右上角运行 **MiniPay Backend**。

第一次打开项目后，若右上角没有立刻出现配置，执行 **File → Reload All from Disk** 或重启 IDEA；IDEA 会读取 `.idea/runConfigurations` 下的 XML 文件。

## 配置内容如何学习

每个 Spring Boot 配置都由三个关键部分组成：

```xml
<module name="payment-service" />
<option name="SPRING_BOOT_MAIN_CLASS" value="com.minipay.payment.PaymentServiceApplication" />
<envs>
  <env name="SERVER_PORT" value="8082" />
</envs>
```

- `module` 指 Maven 子模块；它决定 classpath。
- `SPRING_BOOT_MAIN_CLASS` 是 Spring Boot 启动类。
- `envs` 对应 IDEA 配置页的 **Environment variables**，覆盖 `application.yml` 中的 `${变量名:默认值}`。
- Compound 文件的多个 `<toRun>` 就是 IDEA 中“Run Multiple Configurations”的成员。

## 启动顺序

1. Docker Compose 中先启动基础设施。
2. 容器健康后运行右上角的 **MiniPay Backend**。
3. 在 IDEA Terminal 分别运行：

```powershell
cd C:\Users\hp\Desktop\数字马力\mini-pay-ai-frontend
pnpm.cmd --filter @minipay/merchant-web dev
```

```powershell
pnpm.cmd --filter @minipay/ops-web dev
```

## 已处理的本机旧库问题

2026-08-06 已为 IDEA 创建了三套隔离开发库和专用账号，避免使用旧库中不属于当前代码版本的 Flyway 历史：

| 服务 | 数据库 | IDEA 账号 |
|---|---|---|
| Identity | `minipay_identity_idea_dev` | `minipay_identity_idea_app` |
| Payment | `minipay_payment_idea_dev` | `minipay_payment_idea_app` |
| Wallet | `minipay_wallet_idea_dev` | `minipay_wallet_idea_app` |

对应 JDBC URL、账号、密码已经写入四个 `.idea/runConfigurations/MiniPay_*.xml` 文件。它们只用于本机 IDEA 开发；旧的 `minipay_identity`、`minipay_payment`、`minipay_wallet` 没有被 repair、修改或删除。

## 本机端口冲突

上述配置默认 MySQL 为 `3306`。若该端口已被本机 MySQL 占用，需要在各 Spring Boot 配置的 Environment variables 添加相应的 `MYSQL_URL`，并让 Docker Compose 使用不同的 `MYSQL_CORE_PORT`。该端口改动不能只改 IDEA 后端配置，必须与 Docker Compose 保持一致。
