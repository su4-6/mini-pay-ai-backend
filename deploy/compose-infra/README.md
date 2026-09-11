# 先让业务程序有数据库可用

本阶段目的只有一个：**准备好中间件，并证明 Kubernetes Pod 能用正确账号连接它们。**

```text
私有 .env.local -> compose.yaml -> Docker 中间件与数据卷
                                      ↑
Kubernetes 临时检查 Pod -> 宿主机映射端口
```

业务服务、网页和 YShop Server 后面进 Kubernetes；这里只启动 MiniPay MySQL/Redis、RabbitMQ、Seata、YShop MySQL/Redis 六项。Coturn 留在 Compose 的 `voice` profile，语音阶段另验收。

## 你现在怎么做

先启动 Docker Desktop，确认 Linux 引擎和 Kubernetes 正常。打开后端纯净仓库根目录的 PowerShell，执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/infra.ps1 -Action Start -InfraHost 192.168.65.254
```

这条命令会：

1. 检查私有变量、同名环境变量覆盖、七个中间件定义、文件路径和四个 YShop SQL。
2. 启动或更新六个中间件容器，保留原有数据卷及密码。配置变化可能重建对应容器。
3. 验证 MySQL 业务账号/数据库隔离/Seata 表，YShop 表和 Redis 密码。
4. 在明确的 `docker-desktop` context 创建/保留 `minipay` Namespace，创建一个唯一命名的临时诊断 Pod。
5. 从该 Pod 登录两套 MySQL/Redis，检查 RabbitMQ/Seata TCP 和 RabbitMQ API 账号/权限。凭据经标准输入传入，不放进 Pod YAML、日志或命令参数。
6. 只清理本次创建且 UID 匹配的诊断 Pod，保留 Namespace、中间件和数据。写入不含密码的阶段报告。

成功输出包含 `[PASS] Pod -> ...`。只有容器 Healthy 没有这些输出，还不能说跨环境连接通过。任何失败会 `[FAIL]` 并停止；把这一段输出发回，不用复制私有文件。

`Start` 不创建业务服务、不修改云服务器、不推送镜像、不执行 `down -v`，不重新导入已有数据库卷。重复运行不会生成新密码；已有卷初始化不完整时检查会失败，需要按失败点修复，不能删卷重试。

## 想单独检查时

2026-09-07 本机 Pod 连通性验收已通过，使用 `-InfraHost 192.168.65.254`。默认的 `host.docker.internal` 在本机 Pod 中解析失败；本机重装或网络变化后需重新验收，服务器不得照搬此地址。

已启动的中间件无需重复 Start，复查使用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/infra.ps1 -Action Probe -InfraHost 192.168.65.254
```

`InfraHost` 是 Pod 访问中间件的目标地址；`INFRA_BIND_HOST` 是 Compose 发布端口的监听地址，两者职责不同。保留本地监听 `127.0.0.1`，不要为了 Pod 访问将其改成 `192.168.65.254` 或 `0.0.0.0`。

当前 `compose.yaml` 可继续复用，只含中间件；根目录旧 Compose 保留作迁移/回滚参考，不用于新业务部署。云端复用模板时仍须配置服务器监听地址、真实凭据和数据恢复来源。本地初始化 SQL 覆盖文件不作为生产数据迁移文件。

| 动作 | 内容 | 会改变什么 |
|---|---|---|
| `-Action Validate` | 检查本地文件、私有变量与 Compose 解析 | 仅写检查报告，不要求 Docker 引擎运行 |
| `-Action Check` | 上述检查 + 已启动容器、账号、表结构 | 仅写检查报告 |
| `-Action Probe` | Check + Kubernetes 到宿主机连接 | 创建/保留 Namespace、临时诊断 Pod，最后清理本次 Pod |
| `-Action Start` | 按依赖启动六项，再执行 Check/Probe | 更新本项目中间件容器，保留数据，临时检查 Pod |

报告在 `generated/infra-<action>.json`，目录已被 Git 忽略。看报告的时间与 action，不把历史成功当作当前成功。

## 每个文件在做什么

| 文件 | 用途 |
|---|---|
| `.env.local` | 当前本机真实密码、端口、YShop 源码路径；禁止提交 |
| `.env.local.example` | 变量说明示例；不能拿演示密码启动真实环境 |
| `compose.yaml` | 声明七个中间件容器、端口、数据卷和健康检查 |
| `compose.bootstrap-local.yaml` | 仅首次建本地 YShop 空数据库时挂载四个 SQL |
| `init/minipay-bootstrap.sh` | 首次创建 MiniPay 六库及独立账号 |
| `health/mysql.sh` | 真实执行登录查询，避免 `mysqladmin ping` 将鉴权失败也当作健康 |
| `seata/application.yml` | Seata 2.6.0 事务协调器的独立配置，8091、file 注册模式；没有 7091 Web 控制台 |
| `scripts/k3s/infra.ps1` | 统一操作入口；常见检查不用再手打多层引号命令 |
| `scripts/k3s/infra-common.ps1`、`checks/` | 编码、参数传递、脱敏与检查实现，可以打开阅读 |

初次没有 `.env.local` 才运行 `import-local-secrets.ps1 -YShopBoot3Dir <源码目录>`。已有文件会原样保留。旧环境的 Seata 控制台三个值暂时保留但不使用。更换密码属于独立数据库操作，不能通过 `-Force` 重生成文件完成。

## 本阶段还不能证明什么

- TCP 成功不等于 Seata TCC 或 RabbitMQ 业务事件正确，后续用 Payment/Wallet/Commerce 验证。
- YShop 表存在不等于业务数据、门店、菜单和下单正确；原始 SQL 仅供隔离本地初始化，不能复制发布到生产。
- 健康不等于备份可恢复，迁移前必须完成备份/恢复与数据持久化检查。
- 临时检查 Pod 的网络结果只证明当前命名空间/网络条件；业务 Pod 上线后还要用实际应用检查。
- 当前仍是本地 Docker Desktop Kubernetes；云端 K3s 配置、镜像、HTTPS、网页/App 仍待完成，见 [总蓝图](../../docs/k3s-deployment-blueprint.md) 和 [验收台账](../../docs/k3s-acceptance.md)。

连不上宿主机时不要直接改 `0.0.0.0`。保留报错，检查宿主机地址解析、绑定接口和防火墙，再给出最小修复。
