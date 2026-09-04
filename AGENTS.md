# MiniPay AI Agent Instructions

本文件是所有 AI 智能体在此仓库内工作的强制入口。开始分析或修改代码前，必须完整阅读：

1. `docs/architecture.md`
2. `docs/project-standards.md`
3. 目标服务目录下的源码、迁移和测试

若用户指令与本文件冲突，以用户明确指令为准，但必须指出安全、资金一致性或兼容性风险。

## 不可违反的架构规则

- 服务只能通过版本化 API、可靠事件或 Seata TCC 分支协议协作；禁止跨服务数据库查询或写入。
- Java 模块之间禁止依赖其他业务服务模块；共享内容只能是无业务语义的技术组件或契约文件。
- 依赖方向固定为 `interfaces -> application -> domain`；`infrastructure` 实现 application/domain 定义的出站端口。
- `domain` 不得依赖 Spring、MyBatis、Seata、HTTP、MQ 或数据库注解。
- 支付编排与外卖业务是两个独立服务；外卖不得直接修改支付状态，支付不得修改外卖订单。
- 余额与复式账本由钱包服务独占写入，并在同一本地事务内保持一致。
- AI 服务只能调用白名单工具/API，不得直接访问业务数据库，不得接收登录密码、支付密码、验证码、完整 Token 或私钥。

## 一致性规则

- 跨钱包分片的站内转账、钱包支付和内部资金冲正使用短时 Seata TCC。
- 外卖订单与支付、外部通道回调、注册开户使用本地事务 + Outbox/Inbox + 幂等状态机，不得使用长事务 TCC。
- TCC Try/Confirm/Cancel 必须处理幂等、空回滚和防悬挂；Confirm/Cancel 必须可无限安全重试。
- 业务数据与 Outbox 事件必须在同一个本地事务提交；消费者必须以 `event_id` 幂等。
- 禁止直接修余额、删除账本分录或修改已完成账务事务；纠错只能通过冲正。

## 数据与安全规则

- 金额统一使用人民币分的 `long/BIGINT`，禁止 `float/double` 和隐式单位转换。
- 时间在 Java 中使用 `Instant`，数据库使用 UTC `DATETIME(6)`，API 使用 ISO-8601。
- API 标识使用 UUIDv7；业务单号单独生成并建立唯一约束。
- 所有数据库变更必须通过新的 Flyway 迁移；禁止修改已经合并或发布的迁移。
- SQL 必须显式列名；禁止 `SELECT *`、无界列表、字符串拼接 SQL 和无条件批量更新/删除。
- Access Token 必须校验签名、issuer、audience、过期时间和 scope。
- 登录密码与支付密码分开强哈希；支付密码只进入身份服务，支付服务只接收一次性授权令牌。
- 日志不得包含密码、验证码、完整手机号、详细地址、密钥、Cookie、Authorization 头或完整 Token。
- 任何新增密钥、口令或连接串都必须来自环境变量/Secret；本地默认值只能存在于演示配置。

## API、消息与编码规则

- 公共 API 使用 `/api/v1`，支付开放 API 使用 `/openapi/v1`，内部 API 使用 `/internal/v1`。
- 成功响应直接返回资源；错误使用 RFC 9457 Problem Details，并扩展稳定 `code` 与 `requestId`。
- DTO、领域对象和持久化对象必须分离；Controller 不得返回 MyBatis PO。
- 事件信封必须包含 `eventId`、`eventType`、`aggregateType`、`aggregateId`、`occurredAt`、`traceId`、`payloadVersion`、`payload`。
- 不创建无意义的 BaseController、BaseService、BaseEntity 或跨领域通用 DTO。
- 新增基础设施、跨服务依赖或修改事务模式前，先更新架构文档并说明理由。

## 修改流程

1. 明确需求影响的服务、数据所有者和一致性模式。
2. 先阅读现有接口、迁移、状态机和测试，避免创建重复概念。
3. 以最小变更实现；不得顺手重构无关模块。
4. 新行为必须有自动化测试；缺陷修复必须先覆盖失败场景。
5. 至少运行受影响模块测试；公共配置、契约或基础设施变更必须运行根目录 `mvn verify`。
6. 数据库或消息变更还需运行对应 Testcontainers 集成测试。
7. 交付时说明修改内容、验证命令、未覆盖风险和迁移/配置要求。

## 完成标准

- 编译、单元测试、架构测试和相关集成测试通过。
- 服务边界、租户过滤、幂等与事务边界未被破坏。
- 新 API、事件、环境变量与迁移已记录。
- 无凭据、敏感数据、调试后门、跳过鉴权或直接余额更新。

