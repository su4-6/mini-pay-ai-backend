# K3s + Compose 验收台账

更新时间：2026-09-10。此表防止“文件写完 = 部署成功”的误判。

## 最新本地验收（优先于下方历史记录）

### 2026-09-10 本地全链路与页面验收

- 14 个业务 Deployment、14 个 Service/Ready Endpoint、Gateway 和 10 个 HTTPRoute 通过；证据为 `deploy/k3s/generated/local-platform.json`。
- Compose 七项中间件、Pod 到两套 MySQL/Redis、RabbitMQ、Seata 及账号/库隔离复验通过；证据为 `deploy/compose-infra/generated/infra-probe.json`。
- 真实业务烟测通过：消费者登录、钱包、充值、提现、余额支付、转账/TCC 成功链、外卖授权与绑定、handoff 防重放、附近门店、报价、下单幂等以及 Agent 任务创建/查询。
- 商户端、运营端、系统管理端、外卖 H5、外卖管理端五个主页均返回 200；运营 OAuth 正确跳转到带 `:18080` 的 Identity 与回调地址。浏览器复验的 Food H5 与 YShop Admin 均为 0 控制台错误。
- 修复 YShop 旧表 32 位 ID 溢出（错误日志与订单取餐号）和 UUIDv7 用户名截断碰撞；YShop 38 个 Reactor 模块完整 `verify` 成功。
- 两套 MySQL 和两套 Redis 依次重启后恢复 Healthy，已创建的 YShop 用户与订单仍在；全部业务 Pod 随后重建并再次通过业务烟测。
- Android APK/真机、语音 Coturn、生产 HTTPS/DNS 及回滚演练仍是独立门槛，本次未冒充通过，也未部署网站。

### 2026-09-09 YShop Lite 切换前证据

- 后端 `clean verify` 的前 37/38 个 Reactor 模块通过；最后 `yshop-module-shop-biz` 在补齐隔离 H2 测试清理脚本并放宽测试字段后，`MaterialServiceImplTest` 7 项为 0 失败、0 错误、2 项上游原有跳过。系统模块 219 项为 0 失败、0 错误。
- 关键契约/行为测试通过：MiniPay Food 7 项、管理员认证 12 项、OAuth Token 4 项、退款库存幂等 2 项、会员认证 1 项。
- 运行时依赖边界检查通过；Lite JAR 为 116.56 MiB，相对 160.5 MiB 基线缩小 27.38%。
- 管理端产物 17.28 MiB，相对 29.33 MiB 基线缩小 41.09%；只从生产产物排除未引用的重复 `UEditor22`，商品页仍保留 `/UEditor/`。Food H5 为 1.72 MiB，未超过 2.05 MiB 基线。
- 三个本地镜像 `0.1.0-k3s.2-lite.1` 已构建；两个 Web 镜像按 Pod 的非 root、临时可写目录和 Service DNS 条件执行 `nginx -t` 均通过。
- `local-yshop-lite` 使用默认路径安全成功渲染，仅含 3 个 Deployment 和 3 个 Service；三个工作负载均有非 root、只读根文件系统和 startup/readiness/liveness 探针。YShop Server 改为逐项注入最小变量，不再整包继承全局 ConfigMap/Secret。
- **尚未执行**镜像导入、三个 Deployment 切换、Pod Ready/Endpoint/HTTPRoute/业务回归；集群中仍是旧 YShop 回滚基线，不能把本段写成 Lite 已部署。

2026-09-08 已重新运行 `infra.ps1 -Action Probe -InfraHost 192.168.65.254` 并通过；随后完整执行本地部署与 `acceptance-local.ps1`，证据 `deploy/k3s/generated/local-platform.json` 的状态为 `technical-platform-passed`，记录时间为 `2026-09-08T07:53:26.7552759Z`。

- 六个常驻中间件 Healthy；MySQL 账号、库隔离与所需表、Redis 认证通过。
- 真实诊断 Pod 到两套 MySQL、两套 Redis 的认证通过。
- Pod 到 RabbitMQ/Seata TCP、RabbitMQ 管理 API 认证和默认 vhost 权限通过。
- 默认 `host.docker.internal` 在 Pod 内解析失败；本地继续使用已验收的显式宿主地址，服务器不得复制该地址。
- 14 个固定标签镜像均已导入节点；14 个 Deployment 均 `1/1 Ready`，14 个 Service 均有 Ready Endpoint，全部健康接口通过。
- Gateway 为 `Programmed=True`，10 个 HTTPRoute 均通过 `Accepted=True` 与 `ResolvedRefs=True`。
- YShop 已修正旧数据库名、Spring Profile/日志映射和本地无凭据 SDK 占位；真实第三方凭据未写入镜像或公开配置。
- 技术平台通过不代表业务通过；TCC、RabbitMQ 发布消费、OAuth 浏览器、逐页、Android、数据重建和备份恢复仍待验收。

状态只用：待实现、待运行验证、已通过、失败。每次验收保留时间、环境、镜像版本、步骤、结果；接口证据只留 HTTP 状态、requestId/业务单号和脱敏日志。

## 当前证据

| 检查 | 状态 | 证据与限制 |
|---|---|---|
| 旧草稿备份、根 Compose 保留 | 已通过 | `.local-backup/k3s-pre-rebuild/20260904-initial-drafts/`；现有 Git diff 未改根 Compose |
| 私有环境文件 Git 忽略 | 已通过 | 已检查 ignore；现有值保留 |
| 中间件 Compose 静态配置 | 已通过 | 2026-09-06 `infra.ps1 -Action Validate`；含七项定义、四个 SQL 挂载、UTF-8 中文路径 |
| 部署脚本回归测试 | 已通过 | Windows PowerShell 5.1 实测引号、中文、SQL stdin、Bash 字面值、错误码与脱敏；重复导入和 Force 拒绝后私有文件字节保持一致；三个 Shell 脚本语法检查通过 |
| 网络检查器自动化测试 | 已通过 | 四个模拟测试覆盖成功、连接失败、认证失败、权限不足；不是实际 Pod 网络验收 |
| 后端根构建与测试 | 已通过（有限覆盖） | JDK 21 下 `mvn -B -ntp verify` 成功；测试报告共 338 项，0 失败/错误，24 项跳过，不能代替 Docker 集成及业务验收 |
| MiniPay MySQL 初始化 | 已通过（历史） | 用户先前输出 Healthy、六个 Initialized database、bootstrap completed；不代表今天正在运行 |
| MySQL 新健康检查、业务账号及权限、Seata 表 | 已通过 | 2026-09-07 Probe：六库隔离、应用账号、Seata 表均通过；TCC 业务行为另验 |
| YShop SQL 与 Redis/RabbitMQ/Seata 启动 | 已通过（基础设施） | YShop 表/账号、两套 Redis、RabbitMQ API/vhost、Seata TCP 通过；业务事件另验 |
| 实际 Pod 到 Compose | 已通过（技术平台） | 诊断 Pod 认证通过；14 个工作负载 Ready，健康检查与当前依赖错误日志扫描通过 |
| 本地 Kubernetes 技术平台 | 已通过 | 2026-09-08：14 Deployment、14 Ready Endpoint、14 健康检查、Gateway、10 HTTPRoute；证据 `deploy/k3s/generated/local-platform.json` |
| 备份、恢复、重建后持久化 | 待运行验证 | 不通过删除现有卷做测试；使用隔离恢复目标 |
| Coturn 音视频中继 | 待实现/运行验证 | Compose profile 已有；公网 TCP/UDP、ICE/TURN、客户端呼叫另验 |

本轮已实际应用完整 local overlay，但未推送镜像、未修改 DNS/代理/hosts，也未进入服务器切换。

## 五个交付阶段

| 阶段 | 交付结果 | 状态 |
|---|---|---|
| 1 中间件 | 六个常驻组件就绪、实际账号正确、诊断 Pod 能连接；单列持久化与语音待验项 | 已通过（基础连接） |
| 2 第一条业务链 | Identity 镜像 -> Pod -> Compose MySQL/Redis -> 身份接口 | 已通过（Pod/健康）；浏览器登录待全链路 |
| 3 第一条网页链 | Merchant Web、入口规则、Management BFF、Identity 技术链已 Ready；真实 OAuth 回调另验 | 已通过（技术平台）；业务待验 |
| 4 全业务/页面/App | 14 个工作负载技术平台已通过；Wallet/Payment/Commerce/YShop/Agent/页面/Android 业务矩阵另验 | 已通过（技术平台）；业务待验 |
| 5 云端 K3s | 数据恢复、固定镜像、80/443、证书、全矩阵复测和回滚演练 | 待实现 |

### 2026-09-07 Java 镜像构建证据

- 已将源码验证与运行镜像封装分离：整仓只执行一次 `clean verify`，每个镜像不再重复编译全仓。
- 本机 Microsoft JDK 21.0.12 完成 8 个 Java 模块干净构建；123 份测试报告共 338 项，0 失败、0 错误、0 跳过，Testcontainers 已实际连接 Docker Desktop 并执行 MySQL 8.4 迁移测试。
- Seata 部署契约已改为验证 `deploy/compose-infra`，不再以根目录旧 Compose 为新部署真相。
- 14 个 `0.1.0-k3s.1` 本地镜像已构建并导入节点，尚未推送 Docker Hub。
- Identity、Agent、Consumer BFF、Management BFF、Admin BFF 的 Redis Secret 已实际注入；当前 Pod 健康与日志扫描通过，业务会话行为另验。

## 所有业务工作负载（不得漏项）

每一行都要经过配置源核对、镜像构建/可拉取、Deployment/Probe、Service/EndpointSlice/DNS、依赖鉴权、业务验收。Gateway 条件只在该组对外入口配置后检查。

| 工作负载 | 当前状态 |
|---|---|
| identity-service | 已通过（技术平台）：1/1 Ready；浏览器/OAuth 业务待验 |
| wallet-service | 已通过（技术平台）：1/1 Ready；账本/TCC 业务待验 |
| payment-service | 已通过（技术平台）：1/1 Ready；支付编排/TCC 业务待验 |
| commerce-service | 已通过（技术平台）：1/1 Ready；订单/RabbitMQ 业务待验 |
| agent-service | 已通过（技术平台）：1/1 Ready；SSE/模型/白名单工具待验 |
| consumer-bff | 已通过（技术平台）：1/1 Ready；会话聚合待验 |
| management-bff | 已通过（技术平台）：1/1 Ready；OAuth/Cookie 待验 |
| admin-bff | 已通过（技术平台）：1/1 Ready；OAuth/Cookie 待验 |
| merchant-web | 已通过（技术平台）：1/1 Ready；逐页与 OAuth 待验 |
| ops-web | 已通过（技术平台）：1/1 Ready；逐页待验 |
| admin-web | 已通过（技术平台）：1/1 Ready；逐页待验 |
| yshop-server | 旧基线已通过（技术平台）：1/1 Ready；Lite 镜像已构建，尚未切换或业务验收 |
| yshop-food-h5 | 旧基线已通过（技术平台）：1/1 Ready；Lite 镜像已构建，尚未切换或真机验收 |
| yshop-admin-web | 旧基线已通过（技术平台）：1/1 Ready；Lite 镜像已构建，尚未切换或逐页验收 |

## 页面与 App 门槛

- Merchant/Ops/Admin：从真实前端路由逐页生成清单，再验证登录、首页、所有菜单、刷新/深链接、静态资源、API、OAuth 回调、退出和越权拒绝。当前逐页清单未完成，不能声称所有页面已覆盖。
- YShop Admin：动态菜单也来自数据库，需与前端路由一起枚举；门店、商品、菜单、订单、上传与 WebSocket 全部验收。诊断页面按合同受控禁用。
- Food H5/Android：登录、授权、绑定、门店/菜单、地址/地图、报价、创建订单、支付结果、订单状态、钱包、Agent/SSE、聊天/语音、协议与隐私链接。
- 外卖证据：Commerce 订单、YShop 对应订单、RabbitMQ 事件、Payment 支付单、回调和最终状态一致。异常/重试/重复请求不能产生重复业务效果。
- 所有 URL、回调、HMAC、OAuth Client 与供应商 Key 要逐项核对来源与双方匹配；供应商凭据可读取不代表权限/额度/域名白名单可用。
- 生产 APK 的私有属性文件必须由构建入口显式加载；包名、签名、地图 SHA 指纹、浏览器回调都要用实际 APK 验证。

## 服务器切换门槛

- 项目约定资源 4 核/8GB/80GB，实际负载另测。
- 锁定应用与中间件镜像 digest、K3s/NGF/Gateway API/cert-manager 兼容版本；确认 Docker Hub 私有仓库权限。
- 验证服务器内部绑定地址、防火墙、K3s Pod 到 Docker 中间件的实际连接，不直接照搬 Desktop 地址。
- 在改动前备份原数据、环境文件、密钥、Nginx/证书并验证可恢复；复用历史 HMAC/加密材料以读取既有数据。
- K3s 禁用 Traefik、保留 ServiceLB；先用临时入口验证路由/证书，再释放旧 Nginx 80/443 并切换。
- DNS-01 签发/续期、Full(strict)、公网内部/诊断路径拒绝、TLS与Cookie/OAuth正确。
- 所有网页、Android、支付和外卖复测；同一服务器切换失败时同时恢复端口归属、旧服务和必要的 DNS，不把“改回 DNS”当成单机万能回滚。

## 已修正与仍需跟踪的问题

| 问题 | 处理 | 状态 |
|---|---|---|
| Gateway/HTTPRoute 被画成逐跳处理程序 | 总蓝图区分规则生效链与实际请求链 | 已修正文档 |
| `mysqladmin ping` 不能证明认证成功 | 改用认证 SELECT 健康检查；阶段检查各账号/表 | 静态已验证，运行待验证 |
| PowerShell 嵌套引号导致 SQL 1064 | 查询脚本经 UTF-8 stdin 传入；增加引号和中文回归测试 | 脚本测试已通过，数据库运行待验证 |
| 密码文件 Force 重置导致与旧卷失配 | 已存在则保留；Force 拒绝；首次 CreateNew 防覆盖 | 脚本测试已通过 |
| Seata 虚构 7091 控制台、旧配置依赖 | 独立协调器配置，只暴露8091；file 模式保持 | 静态已验证，运行待验证 |
| Pod 地址仅靠推断 | 临时 Pod 分别测试数据库/Redis认证、MQ/Seata网络 | 待运行验证 |
| 本地/云端“一键原样复制”表述 | 共用模板镜像，明确环境覆盖、凭据、数据、客户端构建差异 | 已修正文档 |

检查失败只修有证据的配置问题；如发现源码缺陷，记录阻塞及证据，业务源码修改需另行明确范围，不伪造通过。
