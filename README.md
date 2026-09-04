# MiniPay AI Backend

新成员首次运行、Web 前端、yshop 和 Android 真机调试请参阅 [MiniPay 本地运行说明](RUNBOOK.md)。

MiniPay AI 后端采用 Java 21、Spring Boot、MySQL、Redis、RabbitMQ 与 Seata 构建。项目以支付正确性为第一原则，在同一 Monorepo 中保持服务独立部署、数据独立所有，并为跨钱包分片的 TCC 事务预留清晰边界。

## 开发前必读

1. [整体架构与分层](docs/architecture.md)
2. [项目工程规范](docs/project-standards.md)
3. [智能体开发约束](AGENTS.md)

## 服务

| 模块 | 默认端口 | 职责 |
|---|---:|---|
| `identity-service` | 8081 | OAuth/OIDC、用户凭证、RBAC、短信登录、支付授权 |
| `payment-service` | 8082 | 支付、退款、转账单与 TCC 编排 |
| `wallet-service` | 8083 | 单一 CNY 钱包库、冻结、复式账本与 Seata TCC 分支 |
| `agent-service` | 8086 | AI 会话、SSE、工具路由与 Trace |
| `consumer-bff` | 8087 | Consumer Web/H5 安全会话和 API 代理 |
| `management-bff` | 8088 | 运营及商户后台安全会话和 API 代理 |

当前初始化阶段只构建身份、钱包、支付、BFF 与 Agent 基础能力。外卖项目集成将在完整支付主链路（注册开户、支付授权、站内转账、钱包付款、退款、账本与对账）通过验收后再另行规划，不作为当前服务启动范围。

## 本地基线

- JDK 21
- Maven 3.9+
- Docker Desktop / Docker Compose

## 工程配套资料

| 位置 | 用途 |
|---|---|
| `contracts/openapi/` | HTTP API 契约；前端 DTO 和服务端契约测试的来源 |
| `contracts/asyncapi/` | RabbitMQ 事件信封和事件版本契约 |
| `docs/开发配套资料/联调记录/` | 前后端联调记录与脱敏证据模板 |
| `.env.example` | 与 `compose.yaml` 配套的本地环境变量示例；仅用于演示，禁止提交真实凭据 |
| `docs/aliyun-oss-sms.md` | 阿里云 OSS、短信真实渠道的开通、凭证、Compose 和手动验证说明 |

具体实施规则见仓库根目录 `contracts/README.md`；产品需求基线见 `docs/MiniPay-AI-PRD-v1.5.0.md`；后端系统分析见 `docs/MiniPay-AI-后端系统分析与设计-v1.7.0.md`。

当前主机若没有 JDK 21，可使用构建容器：

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B -ntp clean verify
```

启动基础设施：

```powershell
docker compose -f docker-compose.yml up -d
```

当前容器拓扑、代码同步结论和本地重建方式见 [当前本地容器环境](docs/local-compose.md)。

启动全部应用：

```powershell
docker compose --profile apps up --build
```

本次 Wallet A/B 合并采用全沙箱重置。若本机曾启动旧拓扑，应先确认当前 Compose 项目名为 `minipay`，再执行 `docker compose down -v` 清理该项目的 MySQL、RabbitMQ 和 Redis 卷，然后重新启动；旧用户、队列消息和资金记录不会迁移。

手动调试时先等待 `mysql-core`、`rabbitmq`、`redis`、`seata-server` 健康，再依次启动 Identity（8081）、Wallet（8083）、Payment（8082）、Agent/BFF。JVM 进程需设置 `SEATA_ENABLED=true`、`SEATA_SERVER_ADDR=localhost:8091`，Wallet 使用 `MYSQL_URL=jdbc:mysql://localhost:3306/minipay_wallet`，Payment 使用 `WALLET_BASE_URL=http://localhost:8083`。

P0 沙箱转账固定使用 Seata TCC。Payment 只启动全局事务并调用 Wallet 的 Debit/Credit Try，Confirm/Cancel 由 Seata Server 回调；Seata 不可用时不会降级为本地或手工转账。当前单 Wallet 数据库并非技术上必须使用分布式事务，这一执行模型用于保持未来物理分片后的兼容性。

Wallet Schema 为 `minipay_wallet`，位于 `mysql-core:3306`，由仅有该 Schema 权限的 `minipay_wallet_app` 账号访问。应用统一通过 `WALLET_BASE_URL=http://localhost:8083` 调用 Wallet，不感知未来分片。

所有默认口令仅用于本地演示。真实密钥不得提交到仓库。

