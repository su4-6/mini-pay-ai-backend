# MiniPay AI Backend

新成员首次运行、Web 前端、yshop 和 Android 真机调试请参阅 [MiniPay 本地运行说明](RUNBOOK.md)。

MiniPay AI 后端采用 Java 21、Spring Boot、MySQL、Redis、RabbitMQ 与 Seata 构建。项目以支付正确性为第一原则，在同一 Monorepo 中保持服务独立部署、数据独立所有，并为跨钱包分片的 TCC 事务预留清晰边界。

## 开发前必读

1. [整体架构与分层](docs/architecture.md)
2. [项目工程规范](docs/project-standards.md)
3. [智能体开发约束](AGENTS.md)

## 服务

| 模块 | 本地端口 | 职责 |
|---|---:|---|
| `identity-service` | 8081 | OAuth2/OIDC 授权服务器、用户凭证与 RBAC、短信/密码登录、图形验证码、支付授权、资料与店铺图签名 |
| `payment-service` | 8082 | 支付、退款、转账单、充值/提现、商户与应用、日汇总指标、图片预签名 |
| `wallet-service` | 8083 | 单一 CNY 钱包库、冻结、复式账本与 Seata TCC 分支 |
| `commerce-service` | 8085 | 点餐/外卖订单、购物车与结算单、门店/菜单/库存、骑手与配送 |
| `agent-service` | 8086 | AI 会话、SSE、工具路由与 Trace（可用工具白名单） |
| `consumer-bff` | 8087 | Consumer Web/App 安全会话和 API 代理 |
| `management-bff` | 8088 | 运营端与商户端安全会话和 API 代理（含图片代传 OSS） |
| `admin-bff` | 8089 | 系统管理端安全会话、账号/审计代理与服务健康聚合 |

外卖（YShop）以**独立单体**集成：`yshop-server`（48080，独立库 `yixiang_drink`）、`yshop-food-h5`、`yshop-admin-web`。
它通过 `bridge-contract` 与支付/钱包协作，不跨库读写业务数据。当前线上该栈按资源情况可整体下线，
由维护页顶替入口（见 [deploy/k3s/README.md](deploy/k3s/README.md#外卖yshop栈的下线与恢复)）。

## 本地基线

- JDK 21、Maven 3.9+（或使用仓库自带 `mvnw`）、Docker Desktop / Docker Compose

```powershell
docker compose -f compose.yaml up -d                      # 本地全量（中间件 + 应用）
docker compose -f deploy/compose-infra/compose.yaml up -d  # 只起中间件（K3s 场景）
```

没有 JDK 21 时可用构建容器：

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B -ntp clean verify
```

手动调试时先等待 MySQL、RabbitMQ、Redis、Seata 健康，再依次启动 Identity（8081）、Wallet（8083）、
Payment（8082）、Commerce（8085）、Agent（8086）与三个 BFF。JVM 进程需设置
`SEATA_ENABLED=true`、`SEATA_SERVER_ADDR=localhost:8091`。

## 关键一致性约定

- 跨钱包分片的站内转账、钱包支付与内部资金冲正使用 **Seata TCC**；Confirm/Cancel 必须幂等、可无限重试，并处理空回滚与防悬挂。
- 外卖订单、通道回调、注册开户走 **本地事务 + Outbox/Inbox + 幂等状态机**，不用长事务 TCC。
- 余额与复式账本由钱包服务**独占写入**，并在同一本地事务内保持一致；纠错只能冲正，禁止直接改余额或删分录。
- 金额统一使用人民币分的 `long/BIGINT`；时间在 Java 用 `Instant`、库用 UTC `DATETIME(6)`。
- 所有数据库变更必须通过新的 Flyway 迁移；SQL 必须显式列名。

## 线上部署（腾讯云单节点 K3s）

完整清单、操作命令与回滚方式见 **[deploy/k3s/README.md](deploy/k3s/README.md)**，要点：

- **K3s 管业务与入口，Compose 管中间件**：中间件（MySQL×2 / Redis×2 / RabbitMQ / Seata）跑在宿主机 `deploy/compose-infra/compose.server.yaml`，
  业务与前端跑在 K3s；集群通过 `10.0.0.16:<映射端口>` 访问中间件。
- **入口网关是 NGINX Gateway Fabric 2.7.0 + Gateway API v1.6.1**（`deploy/k3s/base/routes/` 定义 11 条 HTTPRoute）；
  仓库里早期那套单机静态 nginx 反代（`deploy/nginx/`）与「三仓库 Compose 生产栈」
  （`compose.production.yml` / `compose.edge.yml` / `scripts/deploy-production-stack.sh` 等）
  **已在迁移到 K3s 后删除**，需要时从 Git 历史取回。
- 清单分四层 overlay：`base` → `local`（本地）→ `server`（线上）→ `server-lite`（小规格服务器内存瘦身）。
  镜像按 **digest 锁定**（`overlays/server/private/digests/`，不进 Git），版本记录在 `deploy/k3s/generated/image-digests.json`。
- 边缘：Cloudflare 负责 DNS、TLS 与静态缓存；`integrations/cloudflare/landing-worker/` 是个人主页 + 项目落地页 Worker，APK 走 R2 自定义域名。
- 外卖「维护中」页面：`deploy/k3s/maintenance/`（镜像 + HTML），入口路由在 `base/routes/yshop-routes.yaml`。
- NGF 资源固化：数据面 resources 由 `base/config/nginx-proxy.yaml`（NginxProxy）声明，
  控制面资源/滚动策略由 `scripts/k3s/apply-ngf-tuning.sh` 固化（幂等，可重复执行）。

线上入口（8 个，均 200）：

| 入口 | 说明 |
| --- | --- |
| `ops.su46proj.site/ops/` · `merchant.su46proj.site/merchant/` · `admin.su46proj.site/` | 三个控制台 |
| `su46proj.site` / `www` · `pay.su46proj.site` | 个人主页 · 项目落地页（Worker 渲染） |
| `download.su46proj.site/downloads/minipay-latest.apk` | Android 包（R2） |
| `food.` / `food-admin.` | 外卖维护页（外卖栈下线期间） |

演示账号（密码 `MiniPay@123456`）：运营 `13800138000` · 商户 `13900000009` · 管理员 `13800138002`；
yshop 后台 `admin/admin123`。

## 外部依赖配置（不配也能跑，但对应功能不可用）

| 能力 | 需要的配置 | 位置 |
| --- | --- | --- |
| 图片存储（店铺图/头像） | `OBJECT_STORAGE_PROVIDER=aliyun` + `ALIYUN_OSS_ENDPOINT/REGION/BUCKET` + AK/SK | `payment-runtime`（出签名）与 `identity-runtime` Secret，见 `overlays/*/private/*.env`（不进 Git） |
| 短信验证码 | `SMS_PROVIDER` + 阿里云短信凭证；演示环境用固定验证码 `123456` | `identity-runtime` |
| 邮件验证码 | `EMAIL_PROVIDER=smtp` + SMTP 凭证。**未配置时请把 `MANAGEMENT_HEALTH_MAIL_ENABLED` 设为 `false`**，否则 `/actuator/health` 会因邮件指示器恒为 503 | `identity-runtime` / Deployment env |
| 实名与内容安全 | `REAL_NAME_PROVIDER` / `CONTENT_SAFETY_PROVIDER` | `identity-runtime` |
| 高德地图 | Web 端 JS Key + jscode（前端构建期）、Android Key（App 构建期）、`YSHOP_MINIPAY_AMAP_WEB_KEY`（yshop） | 前端 `.env` / `local.properties` / `runtime.env` |

## 工程配套资料

| 位置 | 用途 |
|---|---|
| `contracts/openapi/` · `contracts/asyncapi/` | HTTP 与事件契约，前端 DTO 与契约测试的来源 |
| `deploy/k3s/README.md` | 线上/本地 K3s 部署、网关、overlay、运维与回滚 |
| `deploy/compose-infra/README.md` | 中间件 Compose（本地与服务器两套） |
| `scripts/k3s/` | 构建、推送、引导（`bootstrap-server.ps1`）、NGF 调优、演示账号 SQL |
| `docs/开发配套资料/联调记录/` | 前后端联调记录与脱敏证据模板 |
| `docs/aliyun-oss-sms.md` | 阿里云 OSS、短信真实渠道的开通与验证 |
| `.env.example` · `.env.production.example` | 环境变量名契约（本地 / 线上）；线上真实取值放 `overlays/server/private/*.env`，禁止提交真实凭据 |

当前初始化阶段只构建身份、钱包、支付、商务、Agent 与三个 BFF。所有默认口令仅用于本地演示，真实密钥不得提交到仓库。
