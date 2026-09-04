# 联调记录：`INT-20260731-01`

| 项目 | 内容 |
|---|---|
| 日期 / 执行人 | 2026-08-03 / Codex |
| 契约文件与版本 | Identity API v1.1.0；Payment API v1.2.0；Wallet API v1.3.0；Events v1.3.0 |
| 服务版本 / 环境 | `0.1.0-SNAPSHOT`；JDK 21；MySQL 8.4 Testcontainers |
| 页面 ID 与关联 FR/BR/AC/TC | APP-02A、APP-09、APP-11～APP-15、APP-17；BR-02A、BR-03、BR-04～BR-09G |
| 请求样例（脱敏） | 尚未进行端到端 HTTP 联调；已完成 Controller/契约编译、服务单测和 Flyway 集成测试 |
| 成功响应样例（脱敏） | 不适用 |
| 异常响应样例（脱敏） | 不适用 |
| `requestId` / 事件 ID | 不适用 |
| 结论 | 不通过（端到端联调待办） |
| 缺陷与后续动作 | 启动 MySQL、Redis、RabbitMQ、Seata、Identity、Wallet、Payment 后，按契约补做成功、权限、字段校验、处理中、失败、事件重放和未来分片兼容恢复联调。生产支付宝、微信和银行卡凭据未提供，当前只能验证沙箱适配器。 |

已完成的自动验证：

- Identity：27 个测试通过；Flyway 7 个迁移通过。
- Payment：12 个测试报告均为零失败；Flyway 9 个迁移通过；新增全额退款与幂等重放测试通过。
- Wallet：4 个测试通过；Flyway 5 个迁移通过。
- Identity、Payment、Wallet 三个模块跳过测试的打包构建通过。
- 四份 OpenAPI/AsyncAPI YAML 均可解析，`git diff --check` 无空白错误。

仍需联调的检查项：字段/枚举/分页/Problem Details、权限边界、页面状态文案、支付授权一次性消费、事件重复投递、跨分片 TCC 故障恢复，以及三端退款状态一致性。
