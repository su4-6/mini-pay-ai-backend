# OAuth audience 与 scope 矩阵

| 主体 | Access Token audience | 路径边界 | scope |
|---|---|---|---|
| OPS 管理员 | `management-api` | Identity `/api/v1/admin/**`、资源服务 `/api/v1/management/**` | `ops.portal`；登录审计额外要求 `ops.audit.read` |
| 消费者 | `consumer-api` | 各资源服务的消费者 `/api/v1/**` | `identity.payment-authorization.write`、`payment.transfer.read/write`、`payment.refund.write`、`wallet.read`、`wallet.recharge.write`、`agent.conversation` |
| Payment → Identity | `identity-internal` | Identity `/internal/v1/**` | `identity.payment-authorization.verify` |
| Payment → Wallet | `wallet-internal` | Wallet `/internal/v1/**` | `wallet.tcc` |
| Agent → Payment | `payment-internal` | Payment `/internal/v1/**` | `payment.tool.invoke` |
| Agent 代表消费者 → Agent | `agent-internal` | Agent 白名单联系人接口 | `agent.contact.read` |
| Agent 代表消费者 → Identity | `identity-internal` | Identity 精确手机号解析接口 | `identity.agent.recipient.resolve` |
| Agent 代表消费者 → Wallet | `wallet-internal` | Wallet AI 查询/聚合接口 | `wallet.agent.summary`、`wallet.agent.bills.read`、`wallet.agent.bills.aggregate` |
| Agent 代表消费者 → Payment | `payment-internal` | Payment AI 意图准备与权威转账结果查询 | `payment.agent.transfer.prepare`、`payment.agent.transfer.read` |
| Agent 代表消费者 → Commerce | `commerce-internal` | Commerce AI 白名单接口 | `commerce.agent.catalog.read`、`commerce.agent.cart.write`、`commerce.agent.checkout.prepare`、`commerce.agent.order.read`、`commerce.agent.cancel.prepare` |

资源服务必须同时验证 JWT 签名、`iss`、`exp`、路径对应的 `aud` 和 scope。用户令牌与服务令牌不可互相替代。OAuth/OIDC 协议端点使用协议规定的错误格式；应用 API 使用 RFC 9457 Problem Details。

Agent 委托令牌通过 RFC 8693 Token Exchange 签发，有效期不超过 2 分钟，并额外绑定 `azp=minipay-agent-service`、`act.sub`、`purpose`、`run_id`、`device_id` 和唯一 `jti`。只接受具有 `agent.conversation` scope、`consumer-api` audience 和设备绑定的 Android Access Token 作为 `subject_token`；不得持久化或记录该原始令牌。
