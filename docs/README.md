# 文档索引

按用途分组；**现行**表示与当前 K3s 线上环境一致，**历史**表示已被取代但保留作追溯。

## 架构与规范（现行）

| 文档 | 说明 |
| --- | --- |
| [architecture.md](./architecture.md) | 整体架构、分层与跨服务边界 |
| [project-standards.md](./project-standards.md) | 工程规范（依赖方向、命名、测试要求） |
| [database-standard-real-data-handover.md](./database-standard-real-data-handover.md) | 数据库与真实数据交接标准 |
| [oauth-security-matrix.md](./oauth-security-matrix.md) | OAuth2/OIDC 客户端、scope 与安全矩阵 |

## 部署与运维（现行）

| 文档 | 说明 |
| --- | --- |
| [../deploy/k3s/README.md](../deploy/k3s/README.md) | **线上/本地 K3s 部署主文档**：网关、overlay、镜像、维护页、外卖下线、运维命令 |
| [../deploy/compose-infra/README.md](../deploy/compose-infra/README.md) | 中间件 Compose（本地 `compose.yaml` 与服务器 `compose.server.yaml`） |
| [../RUNBOOK.md](../RUNBOOK.md) | 新成员首次运行、前端与 Android 真机调试 |
| [../integrations/cloudflare/landing-worker/README.md](../integrations/cloudflare/landing-worker/README.md) | 个人主页 / 项目落地页 Worker 与 R2 下载 |
| [aliyun-oss-sms.md](./aliyun-oss-sms.md) | 阿里云 OSS 与短信真实渠道开通与验证（图片上传依赖它） |

## 业务与需求

| 文档 | 说明 |
| --- | --- |
| [MiniPay-AI-PRD-v1.5.0.md](./MiniPay-AI-PRD-v1.5.0.md) | 产品需求基线 |
| [MiniPay-AI-后端系统分析与设计-v1.7.0.md](./MiniPay-AI-后端系统分析与设计-v1.7.0.md) | 后端系统分析设计 |
| [merchant-b-end-fusion-business-logic-v5.0.0.md](./merchant-b-end-fusion-business-logic-v5.0.0.md) · [merchant-b-end-requirements-traceability.md](./merchant-b-end-requirements-traceability.md) | 商户 B 端业务逻辑与需求追溯 |
| [merchant-end-to-end-acceptance.md](./merchant-end-to-end-acceptance.md) · [merchant-local-development.md](./merchant-local-development.md) | 商户端端到端验收与本地开发 |
| [yshop-minipay-integration.md](./yshop-minipay-integration.md) | 外卖（YShop）与 MiniPay 的集成契约 |
| [management-order-read-apis.md](./management-order-read-apis.md) | 运营端订单只读接口 |
| [业务逻辑实现对照.md](./业务逻辑实现对照.md) · [四人分工方案-v1.0.md](./四人分工方案-v1.0.md) · [模块分工方案-v1.0.md](./模块分工方案-v1.0.md) | 实现对照与分工 |
| [MiniPay-AI-Agent-PRD-v1.0.md](./MiniPay-AI-Agent-PRD-v1.0.md) · [MiniPay-AI-Agent-系统分析与设计-v1.0.md](./MiniPay-AI-Agent-系统分析与设计-v1.0.md) | 智能体（米灵）需求与设计 |
| [merchant-conflict-and-nonmerchant-change-log.md](./merchant-conflict-and-nonmerchant-change-log.md) | 商户相关变更与冲突记录 |

## 历史（本地 Docker Desktop 阶段，已被 K3s 取代）

| 文档 | 说明 |
| --- | --- |
| [k3s-deployment-blueprint.md](./k3s-deployment-blueprint.md) | 迁移前的总蓝图（域名/端口/顺序/回滚的设计稿） |
| [k3s-acceptance.md](./k3s-acceptance.md) | 迁移前的验收台账 |
| [local-compose.md](./local-compose.md) | 本地 Compose 拓扑与重建方式（本地开发仍可参考） |
| [local-debug-acceptance.md](./local-debug-acceptance.md) · [system-admin-local.md](./system-admin-local.md) | 本地调试与本地系统管理员流程 |

> 已删除的历史文档（`production-deployment.md`、`docker-recovery-checkpoint.md`、`idea-one-click-launch.md`、
> `k3s-concentrated-learning.md`）描述的是「三仓库 Compose 生产栈 / 本地一次性流程」，随 K3s 迁移一并移除，需要时从 Git 历史取回。

## 其他目录

| 位置 | 用途 |
| --- | --- |
| `contracts/openapi/` · `contracts/asyncapi/` | HTTP 与事件契约 |
| `deploy/k3s/images/` | yshop 三个镜像的 Dockerfile 与静态 nginx 配置 |
| `scripts/k3s/` | 构建/推送/引导/验收脚本（见该目录 README） |
| `docs/开发配套资料/联调记录/` | 前后端联调记录模板 |
