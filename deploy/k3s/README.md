# MiniPay Kubernetes / K3s 部署入口

最终架构：K3s 管业务与入口，Compose 管中间件。本地先使用 Docker Desktop Kubernetes 验证。

- [总蓝图](../../docs/k3s-deployment-blueprint.md)：为什么这样部署、域名/端口、顺序和回滚。
- [验收台账](../../docs/k3s-acceptance.md)：已通过与待验证，防止漏掉网页、App、数据或语音。
- [当前阶段操作](../compose-infra/README.md)：中间件和 Pod 网络验收，一条阶段命令。

截至 2026-09-08，旧 YShop 基线在内的 14 个固定标签镜像已导入 Docker Desktop Kubernetes；14 个 Deployment、14 个 Service、Gateway 和 10 个 HTTPRoute 已通过运行验收。证据见 `deploy/k3s/generated/local-platform.json`。这只表示旧版本本地技术平台已通过，支付/TCC、RabbitMQ 业务事件、浏览器 OAuth、逐页、Android、数据重建与备份恢复仍须单独验收。

2026-09-09 已在两个 clean 仓库内完成 YShop Lite 的三镜像构建准备：`yshop-server`、`yshop-food-h5`、`yshop-admin-web` 均使用标签 `0.1.0-k3s.2-lite.1`。后端运行时边界检查、关键单元测试、两个 Nginx 镜像配置检查及仅含 3 个工作负载的 Kustomize 渲染已通过；新镜像尚未导入 Kubernetes，旧 YShop Pod 未被替换。切换必须由用户执行下方独立阶段命令，并在业务验收失败时恢复旧标签。

## YShop Lite 三工作负载切换

```powershell
# 构建和测试三镜像；不推送、不部署
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/build-yshop-lite-images.ps1

# 服务端 dry-run、导入三个镜像、只更新三个 YShop 工作负载并等待 Ready
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/deploy-yshop-lite-local.ps1
```

第二条命令会自动发现 Kubernetes 节点，但会改变本地集群；执行前应保留当前三个 YShop 镜像标签作为回滚基线。它不会修改 Compose 数据卷或数据库表。

## Wallet 完整样板：已完成，可用于复查

在仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/deploy-wallet-local.ps1 -Action Import
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/deploy-wallet-local.ps1 -Action Deploy
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/deploy-wallet-local.ps1 -Action Verify
```

三步分别只做：把 Identity/Wallet 镜像导入自动发现的 Kubernetes 节点；仅应用 Identity/Wallet 并等待 Ready；验证健康、EndpointSlice、Wallet 数据库隔离、RabbitMQ、Seata 与 Identity JWK。该样板已通过，通常无需重复执行。

## 全量技术平台的四个批量阶段

```powershell
# 1. 全仓测试、构建并核对 14 个本地镜像；不推送
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/build-all-images.ps1

# 2. 静态规则、私密配置、GatewayClass 与服务端 dry-run；不应用
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/preflight-local.ps1

# 3. 自动导入镜像，按依赖顺序部署并等待 14 个 Deployment Ready
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/deploy-local.ps1

# 4. 验证 Deployment、EndpointSlice、健康检查、Gateway、HTTPRoute 与日志
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/acceptance-local.ps1
```

第一条构建阶段包含：

1. 自动使用本机 JDK 21，先删除旧 `target`，再对 8 个 Java 模块执行一次完整测试（含可运行的 Testcontainers 集成测试）。
2. 测试通过后封装 8 个 Java/BFF、3 个 MiniPay Web、YShop Server、Food H5 和 YShop Admin，共 14 个固定标签镜像。

这些本地阶段不会推送 Docker Hub、不会修改 DNS/代理/hosts，也不会删除或重建 Compose 数据卷。Food H5 需要本机 HBuilderX 编译器；缺失时构建阶段会明确停止。

当前构建镜像使用 [docker/k3s-service.Dockerfile](../../docker/k3s-service.Dockerfile)，构建上下文只允许已验证的 JAR 进入；Java 21 运行时镜像已固定摘要。镜像版本统一记录在 `versions.env.example`。

## 服务器阶段（本地业务验收之后）

`scripts/k3s/push-images.ps1` 由你登录 Docker Hub 后执行；它推送 14 个镜像并生成被 Git 忽略的 digest 组件。服务器 overlay 必须另外准备私密配置、Docker Hub 拉取 Secret、Cloudflare Token 和 ACME 邮箱，通过 `preflight-server.sh` 后才能应用。不得复制本地 `192.168.65.254`。

集中学习安排见 [K3s 全链路集中学习](../../docs/k3s-concentrated-learning.md)：只在本地全链路通过后开始，用真实 Pod、日志和配置集中讲总链路、Wallet/Payment/TCC、OAuth/BFF、YShop/RabbitMQ、Agent SSE 以及固定故障定位顺序。
