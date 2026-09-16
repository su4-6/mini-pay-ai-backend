# MiniPay AI · 后端

MiniPay AI 是一套**支付 + 生活服务（点餐外卖 + AI 助手「米灵」）**的全栈系统。本仓库是它的后端：
8 个 Spring Boot 微服务、独立的钱包与复式账本、跨钱包分片转账用 Seata TCC，外卖以独立单体接入，
全部通过 NGINX Gateway Fabric（Gateway API）暴露在同一套域名之下。

- **在线体验**：运营平台 [ops.su46proj.site/ops](https://ops.su46proj.site/ops/) · 商户平台 [merchant.su46proj.site/merchant](https://merchant.su46proj.site/merchant/) · 系统管理 [admin.su46proj.site](https://admin.su46proj.site/)
- **前端与 App**：[mini-pay-ai-frontend](https://github.com/su4-6/mini-pay-ai-frontend)
- **新人上手**：[RUNBOOK.md](RUNBOOK.md)（本地全量运行、IDE 调试、真机联调） · **部署手册**：[deploy/k3s/README.md](deploy/k3s/README.md)

## 业务能力

| 领域 | 能力 |
| --- | --- |
| 账户与登录 | 手机号 + 密码 / 短信登录、图形验证码、OAuth2 授权码 + PKCE、会话与登出、实名与内容安全对接 |
| 支付 | 下单、支付、退款、支付密码（一次性授权令牌）、收款码、通道回调验签与幂等 |
| 钱包 | 单一 CNY 钱包、冻结/解冻、复式记账账本（借贷必平）、账单与流水查询 |
| 资金流转 | 站内转账（跨钱包分片 TCC）、充值、提现、收款、内部资金冲正 |
| 商户 | 商户入驻申请与审核、商户资料与门店、应用与密钥（开放 API）、日汇总指标 |
| 外卖点餐 | 门店/菜单/库存、购物车与结算、订单状态机、骑手与配送（独立 YShop 单体 + 桥接契约） |
| AI 助手 | 会话与 SSE 流式回复、白名单工具调用（查余额/账单/下单等）、记忆与 Trace |
| 管理端 | 运营总览指标、订单与审核后台、账号与角色、登录审计、服务健康聚合 |

> 演示环境的外卖入口当前显示维护页，外卖服务、源码与镜像都是完整的，可按需启停。

## 架构

```
                  Cloudflare（DNS / TLS / 静态边缘缓存）
                                  │  HTTPS
                     NGINX Gateway Fabric + Gateway API
        ┌───────────────┬───────────────┬────────────────┬───────────────┐
        │               │               │                │               │
   ops-web/        merchant-web/    admin-web/       consumer-bff/   外卖 H5 与后台
   management-bff  management-bff   admin-bff        （App / Web）   yshop-server（独立单体）
        │               │               │                │               │
        └───────────────┴───────┬───────┴────────────────┘          bridge-contract
                                │                                    （订单/支付协作）
        ┌──────────┬────────────┼────────────┬──────────┬───────────┐
   identity    payment       wallet      commerce     agent      （内部 API / 事件）
    身份与凭证   支付与商户    钱包与账本   点餐外卖    AI 助手
        └──────────┴────────────┴────────────┴──────────┴───────────┘
             MySQL（按服务分库） · Redis · RabbitMQ · Seata
```

- **同步调用**只走版本化 API（`/api/v1` 对外、`/internal/v1` 服务间、`/openapi/v1` 开放平台），
  BFF 负责浏览器会话与 CSRF，业务服务之间不共享数据库。
- **异步**走 RabbitMQ 事件（Outbox/Inbox + `event_id` 幂等），跨钱包分片的转账与钱包支付走 Seata TCC。
- **AI 助手**只能调用白名单工具/API，不接触业务数据库，也不接收密码、验证码或完整 Token。

## 服务清单

| 模块 | 端口 | 职责 |
| --- | --- | --- |
| `identity-service` | 8081 | OAuth2/OIDC 授权服务器、凭证与 RBAC、短信/密码登录、验证码、支付授权、资料图签名 |
| `payment-service` | 8082 | 支付、退款、转账单、充值/提现、商户与应用、日汇总指标、图片预签名 |
| `wallet-service` | 8083 | 单一 CNY 钱包库、冻结、复式账本与 Seata TCC 分支 |
| `commerce-service` | 8085 | 点餐/外卖订单、购物车与结算单、门店/菜单/库存、骑手与配送 |
| `agent-service` | 8086 | AI 会话、SSE 流式回复、工具路由与 Trace（工具白名单） |
| `consumer-bff` | 8087 | Consumer Web/App 安全会话与 API 代理 |
| `management-bff` | 8088 | 运营端与商户端安全会话、API 代理与图片代传 |
| `admin-bff` | 8089 | 系统管理端安全会话、账号/审计代理与服务健康聚合 |
| `yshop-server` | 48080 | 外卖独立单体（独立库 `yixiang_drink`、后台与 H5 前端由前端仓库构建） |

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言与框架 | Java 21 · Spring Boot 3.5 · Spring Security / Authorization Server · Spring AI |
| 数据 | MySQL 8（按服务分库 + Flyway 迁移） · Redis（会话与缓存） · RabbitMQ（领域事件） |
| 一致性 | Seata TCC（跨分片资金）· 本地事务 + Outbox/Inbox 幂等（外卖、回调、开户） |
| 入口 | K3s · NGINX Gateway Fabric 2.7 · Gateway API · Cloudflare（DNS/TLS/静态缓存） |
| 契约 | OpenAPI（HTTP）与 AsyncAPI（事件），见 [`contracts/`](contracts/) |
| 可观测 | Spring Boot Actuator（readiness/liveness）、结构化日志、BFF 侧服务健康聚合 |

## 关键设计约定

- **资金正确性优先**：余额与复式账本由钱包服务独占写入，在同一本地事务内保持一致；金额一律用人民币
  **分**的 `long/BIGINT`；纠错只能通过冲正，禁止直接改余额或删分录。
- **事务模式按场景选**：跨钱包分片的转账、钱包支付用短时 Seata TCC（Try/Confirm/Cancel 幂等、可无限重试、
  处理空回滚与防悬挂）；外卖订单、通道回调、注册开户用本地事务 + Outbox/Inbox 状态机，不用长事务。
- **依赖方向固定**：`interfaces → application → domain`，`infrastructure` 实现出站端口；`domain` 不依赖
  Spring/MyBatis/HTTP/MQ；服务之间不做跨库读写。
- **接口契约稳定**：成功直接返回资源，错误用 RFC 9457 Problem Details 并带稳定 `code` 与 `requestId`；
  DTO / 领域对象 / 持久化对象分离，Controller 不返回 MyBatis PO。
- **时间与标识**：Java 用 `Instant`、库存 UTC `DATETIME(6)`、API 用 ISO-8601；主键 UUIDv7，业务单号单独生成并建唯一约束。
- **安全基线**：登录密码与支付密码分开强哈希；Access Token 校验签名、issuer、audience、过期与 scope；
  日志不落密码、验证码、完整手机号、详细地址、密钥与 Token；所有密钥来自环境变量。

## 快速开始

需要 JDK 21、Maven 3.9+（或用仓库自带 `mvnw`）与 Docker Compose：

```bash
# 中间件 + 全部服务（本地全量）
docker compose -f compose.yaml --profile apps up -d --build

# 只起中间件（自己用 IDE 起服务时）
docker compose -f deploy/compose-infra/compose.yaml up -d
```

手动调试时先等 MySQL、RabbitMQ、Redis、Seata 健康，再依次启动 Identity（8081）、Wallet（8083）、
Payment（8082）、Commerce（8085）、Agent（8086）与三个 BFF，JVM 参数需带
`SEATA_ENABLED=true`、`SEATA_SERVER_ADDR=localhost:8091`。没有本地 JDK 时可以用构建容器：

```bash
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B -ntp clean verify
```

详细步骤（含外卖栈、前端与真机联调）见 [RUNBOOK.md](RUNBOOK.md)。

## 部署

线上是单节点 K3s：中间件（MySQL ×2 / Redis ×2 / RabbitMQ / Seata）跑在宿主机 Compose，
业务与前端跑在集群里，入口由 NGINX Gateway Fabric 按 HTTPRoute 分流，TLS 与静态缓存由 Cloudflare 承担。

- **两套 CDN 并存**：页面与接口走 Cloudflare（控制台的静态外壳由 Worker 做边缘缓存）；
  **APK 下载走腾讯云境内 CDN** `dl.su46proj.site`（源站仍是 R2 的 `download.su46proj.site`，国内实测 40 MB 从 156 s 降到 3.4 s）。
- 部署手册（清单分层、构建与推送镜像、网关与证书、外卖栈启停、CDN/证书运维、日常运维与回滚）：[deploy/k3s/README.md](deploy/k3s/README.md)
- 中间件编排：[deploy/compose-infra/README.md](deploy/compose-infra/README.md)
- 环境变量名契约：[`.env.example`](.env.example)（本地）· [`.env.production.example`](.env.production.example)（线上）

## 需要外部配置的能力

不配也能跑，但对应功能不可用（演示环境用固定短信验证码 `123456` 代替真实短信）：

| 能力 | 需要的配置 |
| --- | --- |
| 图片存储（店铺图/头像） | `OBJECT_STORAGE_PROVIDER=aliyun` + `ALIYUN_OSS_*` + AK/SK |
| 短信验证码 | `SMS_PROVIDER` + 阿里云短信凭证 |
| 邮件验证码 | `EMAIL_PROVIDER=smtp` + SMTP 凭证；未配置时需把 `MANAGEMENT_HEALTH_MAIL_ENABLED` 设为 `false`，否则健康检查会因邮件指示器失败 |
| 实名与内容安全 | `REAL_NAME_PROVIDER`（演示环境用 `sandbox`：证件尾号非 0 即通过，且不校验身份证格式）/ `CONTENT_SAFETY_PROVIDER` |
| AI 助手「米灵」 | `MODEL_ENABLED=true` + `MODEL_CHAT_MODE=openai` + `MODEL_BASE_URL` + `MODEL_NAME` + `MODEL_API_KEY`（任意 OpenAI 兼容服务；演示环境接的是智谱 GLM `glm-4.5-air`） |
| 高德地图 | Web JS Key + jscode（前端构建期）、Android Key（App 构建期）、`YSHOP_MINIPAY_AMAP_WEB_KEY`（外卖 H5） |

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/README.md](docs/README.md) | 文档总索引（现行 / 历史分组） |
| [docs/architecture.md](docs/architecture.md) · [docs/project-standards.md](docs/project-standards.md) | 架构分层与工程规范（提交代码前必读） |
| [AGENTS.md](AGENTS.md) | 服务边界、一致性、数据与安全规则的强制约束 |
| [contracts/](contracts/) | OpenAPI 与 AsyncAPI 契约，前端 DTO 与契约测试的来源 |
| [deploy/k3s/README.md](deploy/k3s/README.md) | K3s 部署、网关、overlay、运维与回滚 |
| [docs/aliyun-oss-sms.md](docs/aliyun-oss-sms.md) | 阿里云 OSS / 短信真实渠道的开通与验证 |
| [docs/merchant-end-to-end-acceptance.md](docs/merchant-end-to-end-acceptance.md) | 商户端到端验收用例 |

## 联系

问题或建议：`su_qihang@163.com`
