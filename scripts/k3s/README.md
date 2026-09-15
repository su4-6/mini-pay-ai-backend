# scripts/k3s 脚本索引

分三类：**线上服务器**（当前主流程）、**本地 K8s**（Docker Desktop 阶段，仍可用于本地复现）、**公共/测试**。

## 一、线上服务器（当前主流程）

| 脚本 | 用途 |
| --- | --- |
| `bootstrap-server.ps1` | 在服务器上准备私密配置、`runtime.env`、命名空间与私密 Secret，并把清单渲染到位。支持 `-InfraEnvFile <deploy/compose-infra/.env.local>` 复用中间件真实凭据、`-ReuseCryptoFrom <overlays/server/private>` 复用既有 JWT 密钥对（**重跑前先备份 `private/jwt-*.pem`**） |
| `apply-ngf-tuning.sh` | 固化 NGF（网关）控制面资源与滚动策略；幂等，每次 apply 清单后执行一次。数据面 resources 由 `deploy/k3s/base/config/nginx-proxy.yaml`（NginxProxy）声明 |
| `prepare-local-runtime-env.ps1` | 从示例生成 `runtime.env`（含 `YSHOP_MINIPAY_AMAP_WEB_KEY` 等） |
| `push-and-record-digests.ps1` / `push-images.ps1` | 推送镜像并把 digest 记录进 `deploy/k3s/generated/image-digests.json` |
| `pin-image-digests.ps1` | 按 `image-digests.json` 生成/更新 digest 锁定组件 |
| `ci-pipeline.ps1` · `.github/workflows/build-push-digest-pin.yml` | 构建 → 推送 → 记录 digest 的流水线 |
| `sql/provision-server-demo-accounts.sql` | 服务器演示账号（运营/商户/系统管理员）与角色对齐，幂等 |
| `sql/fix-system-admin-role.sql` · `sql/grant-system-admin.sql` | 系统管理员角色修复/授权（历史修复脚本） |
| `sql/seed-food-menu-and-items.sql` | 外卖菜单与商品演示数据 |
| `preflight-server.sh` | 服务器侧前置检查 |
| `acceptance.ps1` | 线上验收（部署、EndpointSlice、健康、Gateway/HTTPRoute、日志） |

## 二、本地 K8s（Docker Desktop 阶段，可复现本地环境）

| 脚本 | 用途 |
| --- | --- |
| `bootstrap-local.ps1` | 本地 K8s 引导（命名空间、私密配置、`digest-pinned` 组件） |
| `infra.ps1`（+ `infra-common.ps1`、`checks/`） | 本地中间件启停与 **Pod→宿主机连通性探针**，`-InfraHost 192.168.65.254` |
| `build-images.ps1` / `import-local-images.ps1` | 本地构建与导入镜像 |
| `deploy-local.ps1` / `deploy-identity-local.ps1` / `deploy-wallet-local.ps1` | 本地按依赖部署并等待 Ready |
| `import-local-secrets.ps1` / `prepare-local-workload-secrets.ps1` / `create-secrets.ps1` | 本地私密配置生成 |
| `preflight-local.ps1` / `check-config.ps1` / `check-yshop-bootstrap.ps1` | 本地静态检查与 dry-run |
| `acceptance-local.ps1` | 本地验收 |

> 这些脚本服务于「本地先验、再上服务器」的流程，**不要**把本地地址（`192.168.65.254`、`host.docker.internal`）带到服务器。

## 三、公共 / 测试

| 脚本 | 用途 |
| --- | --- |
| `tests/test-infra.ps1` · `tests/echo-input.ps1` · `tests/test_network.py` | 脚本自测与网络检查 |
| `verify-h5-bundle.ps1` | 校验外卖 H5 构建产物完整性 |

## 相关文档

- 部署主文档：[deploy/k3s/README.md](../../deploy/k3s/README.md)
- 中间件 Compose：[deploy/compose-infra/README.md](../../deploy/compose-infra/README.md)
- 文档索引：[docs/README.md](../../docs/README.md)
