# MiniPay 契约目录

本目录是跨端与跨服务的唯一协议事实来源。Controller、BFF、前端 DTO 和契约测试必须与其保持一致。

首次查看请先阅读 [文件说明](文件说明.md)，它说明每个 YAML 的用途和开发时的使用方式。

| 目录 | 内容 | 维护责任 |
|---|---|---|
| `openapi/` | 对客户端与 BFF 可见的 HTTP API | 接口所属服务 |
| `asyncapi/` | RabbitMQ 领域事件信封与载荷 | 事件生产者与消费者共同评审 |

所有 HTTP 契约的错误对象以
`openapi/components/problem-details-v1.yaml#/components/schemas/ProblemDetails`
为唯一结构定义；各 API 中的同名 schema 必须保持兼容。

## 变更规则

1. 新增字段保持向后兼容；删除字段、改变含义、收紧枚举或错误码属于破坏性变更，必须升级版本或提供兼容期。
2. 所有写请求定义 `Idempotency-Key`；成功响应直接返回资源，失败响应为 RFC 9457 `application/problem+json`。
3. 金额使用 `amountCent` 整数，时间使用 UTC ISO-8601，标识使用 UUIDv7。
4. 契约改动必须同时更新：对应系统分析章节、前端 DTO/Mock、服务端契约测试与 `docs/开发配套资料/联调记录` 中的联调记录。

当前 YAML 覆盖 Identity、Wallet、Payment（含转账、银行卡、充值、提现、三种支付方式与退款）、Agent、Management，以及 `commerce-food-yshop-v1.yaml` 中的 yshop 正式外卖接入主链路。通用第三方应用授权、手机号披露补验与解除授权由 `identity-application-authorizations-v1.yaml` 定义。BFF 代理边界以 `docs/architecture.md` 为准，避免重复维护。未列出的路径不代表可自由设计，必须先完成契约评审。
