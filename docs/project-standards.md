# MiniPay AI 项目工程规范

## 1. 基础技术规范

- Java 21，启用 Maven Enforcer；禁止使用预览特性。
- Spring Boot 3.5.x；依赖版本优先由 Spring Boot BOM 管理。
- 原生 MyBatis 3.0.x，不使用 MyBatis-Plus ActiveRecord 或通用业务 CRUD 基类。
- MySQL 8.4、Flyway、HikariCP；测试使用 Testcontainers，不以 H2 代替 MySQL。
- RabbitMQ 配合 Outbox/Inbox；Redis 不作为资金或订单最终状态存储。
- 编码 UTF-8，换行 LF，时区 UTC。

## 2. 命名与包结构

- Maven 模块、URL、表名和消息 routing key 使用小写 kebab/snake/dot 约定。
- Java 类型使用 PascalCase，方法/变量 camelCase，常量 UPPER_SNAKE_CASE。
- 命令以动词开头，如 `PrepareTransferCommand`；查询以 `Query/Get/Search` 开头。
- REST 请求/响应使用 `*Request`、`*Response`；MyBatis 持久化对象使用 `*Po`。
- 领域对象不用 `DO/DTO/VO` 等模糊后缀；使用真实业务名。
- 状态枚举使用稳定英文大写值，不把中文文案持久化为状态。

## 3. Java 与领域代码

- 优先构造器注入；禁止字段注入。
- 不返回 `null` 集合；可选单值使用 `Optional` 仅限返回值。
- 领域模型不得暴露任意 setter；状态变化通过表达业务意图的方法完成。
- 领域状态机必须校验来源状态，非法迁移返回稳定业务错误。
- Controller 只做协议转换、Bean Validation 和调用应用用例。
- 应用服务控制事务边界；一个方法只表达一个用例。
- 禁止捕获 `Exception` 后吞掉；必须转换为领域/基础设施错误或继续抛出。
- 禁止将远程调用放在持有数据库行锁的本地事务中。

## 4. API 规范

- 资源路径使用复数名词；动作仅用于无法自然表达为资源状态变化的场景。
- 创建返回 201，异步处理返回 202，无内容返回 204。
- 成功响应不套统一 `data` 外壳；分页统一包含 `items`、`page`、`size`、`total`。
- 错误采用 `application/problem+json`，至少包含：

```json
{
  "type": "https://docs.minipay.local/problems/insufficient-balance",
  "title": "Insufficient balance",
  "status": 409,
  "code": "INSUFFICIENT_BALANCE",
  "requestId": "request-id",
  "detail": "可安全展示的说明"
}
```

- 客户端依据 `code`，不得解析 `detail`。
- 所有写接口必须定义幂等策略；支付开放 API 使用业务唯一键或 `Idempotency-Key`。
- 列表必须分页并设置最大页大小；查询条件必须有索引设计。
- OpenAPI 是公共接口契约；不兼容变更通过新版本发布。

## 5. 数据库与 MyBatis

- 表和列使用 `snake_case`，InnoDB、`utf8mb4`。
- 主键使用 UUIDv7，MySQL 保存为 `BINARY(16)`；面向用户的业务单号另设唯一列。
- 金额列使用 `BIGINT`，字段名显式带 `_amount` 或 `_amount_cent`；币种使用 `CHAR(3)`。
- 时间列使用 `DATETIME(6)` UTC；审计表保留 `created_at`、`updated_at`。
- 可并发修改的聚合必须有 `version`，使用乐观锁或明确的 `SELECT ... FOR UPDATE`。
- 每个幂等键、外部订单号、事件 ID 和 TCC 分支标识必须有数据库唯一约束。
- Mapper SQL 必须显式列名、参数绑定和结果映射；禁止 `${}` 拼接未验证输入。
- 写 Mapper 返回受影响行数，应用层必须验证预期行数。
- Flyway 文件使用 `V{version}__{description}.sql`；已发布迁移只增不改。
- 生产代码禁止 Hibernate `ddl-auto`、启动时自动重建、无条件清库或演示数据覆盖。

## 6. 事务、账务与幂等

- 本地事务尽可能短，只包含同一数据所有者的数据库操作。
- TCC Try 只预留资源；Confirm 正式扣减/入账并写账本；Cancel 释放预留。
- TCC 三阶段以 `xid + branch_id + action_name` 或等价唯一键防重。
- 账务事务的借方总额必须等于贷方总额；分录写入后不可修改或删除。
- 任何写操作重试前先明确幂等键；不得依赖“用户不会重复点击”。
- Outbox 发布成功前不能删除事件；失败进入退避重试并产生指标/告警。
- Inbox 插入与业务状态迁移应在同一消费者本地事务完成。
- 自动恢复只能重试、查询或创建冲正建议，禁止直接改余额。

## 7. RabbitMQ 规范

- Exchange 按领域定义，事件 routing key 使用 `<domain>.<aggregate>.<past-tense-event>`。
- 消息持久化，生产端开启 Publisher Confirm；消费端显式 ACK。
- 重试使用延迟队列/退避策略，超过阈值进入 DLQ，不无限热循环。
- Payload 必须版本化；新增字段保持向后兼容，删除/改义通过新版本事件。
- 消费者先验证事件版本、必填字段和租户上下文，再执行业务。

## 8. 鉴权与安全

- OAuth Client、用户 Token、服务 Token 使用不同 audience/scope。
- Web Cookie 使用 `HttpOnly`、`Secure`、`SameSite=Lax/Strict` 和 `__Host-` 前缀；状态变更启用 CSRF。
- Android 使用 PKCE 和系统安全存储，禁止把 Refresh Token 写入普通偏好设置。
- 密码使用 Argon2id 并按环境校准成本；修改密码后撤销相关 Refresh Token。
- 短信验证码使用安全随机数，Redis 只存摘要，限制有效期、尝试、重发、手机号/IP/设备频率。
- RBAC 不能替代租户过滤；商户资源查询必须强制附加当前 `merchantId`。
- 日志和 Trace 执行字段级脱敏；任何模型上下文不得包含敏感凭据。

## 9. 日志与可观测性

- 使用结构化 JSON 日志，字段至少包含 timestamp、level、service、traceId、requestId、message。
- 业务关键日志增加 businessNo；TCC 日志增加 xid/branchId，但不得记录完整请求敏感内容。
- 指标名称使用稳定小写命名；必须监控请求延迟、错误率、Outbox 积压、DLQ、TCC 未决事务和账务差异。
- 健康检查区分 liveness/readiness；外部依赖故障不能导致进程无限阻塞。
- 禁止使用 `System.out`、打印堆栈到标准输出或用日志代替指标。

## 10. 测试规范

- 单元测试：领域状态机、金额计算、权限决策和幂等判断。
- 模块测试：应用用例与端口使用 fake/mock，验证事务外行为。
- 集成测试：MySQL、Redis、RabbitMQ 使用 Testcontainers；迁移必须从空库执行。
- 契约测试：支付开放 API、内部 API 和事件 schema。
- 架构测试：使用 ArchUnit 验证分层依赖和禁止跨服务依赖。
- TCC 测试：成功、Try 失败、重复 Confirm/Cancel、空回滚、防悬挂和协调器恢复。
- 安全测试：错误 issuer/audience/scope、CSRF、Refresh Token 复用、租户越权和支付授权重放。
- 缺陷修复必须包含能在修复前失败的回归测试。

## 11. Git、评审与完成标准

- 分支使用 `codex/` 或团队约定前缀；提交保持单一目的。
- 提交信息使用 `feat|fix|refactor|test|docs|build|chore(scope): summary`。
- 不提交生成目录、日志、真实密钥、数据库转储或本地 IDE 配置。
- PR/交付说明必须列出行为变化、迁移、配置项、测试结果和回滚影响。
- 新增依赖必须说明用途，并确认许可证、安全和现有 BOM 兼容性。
- 完成前执行根构建；无法运行的验证必须明确说明原因，不得宣称已通过。

