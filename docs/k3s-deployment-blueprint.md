# MiniPay K3s 全链路改造蓝图

> 更新：2026-09-09。Compose 基础连接与旧版本 14 个本地工作负载技术验收通过；YShop Lite 三镜像已完成构建前验收，但尚未导入或切换。支付/TCC、RabbitMQ 业务事件、OAuth、逐页、Android、持久化/恢复与服务器上线仍未通过。进度以 [验收台账](k3s-acceptance.md) 为准。

## 1. 目标与边界

本改造把无状态业务程序交给 K3s 管理，把有状态基础设施继续留在 Docker Compose。

请求链路（公网业务 HTTP/HTTPS）：

```text
浏览器 / Android
  -> Cloudflare
  -> 云服务器 80/443（K3s ServiceLB 暴露入口 LoadBalancer Service）
  -> 入口 NGINX Pod
  -> 按 HTTPRoute 规则选择 Service 对应的业务 Pod
```

内部基础设施链路：

```text
K3s 业务 Pod
  -> 宿主机可达地址与映射端口
  -> Compose 管理的 MySQL / Redis / RabbitMQ / Seata 容器
```

配置生效链路：`Gateway/HTTPRoute -> NGINX Gateway Fabric 控制器 -> 生成并下发 NGINX 配置`。Gateway、HTTPRoute 是 Kubernetes 资源，不是请求依次经过的代理进程。Service 定义后端；NGINX 控制器可以从端点信息直接配置 Pod 上游，不能断言每次请求必经 ClusterIP。

Coturn 仍由 Compose 管理，但音视频中继是独立 TCP/UDP 链路，不走 HTTPRoute；公网地址、3478 与中继端口、防火墙、客户端 ICE/TURN 参数单独验收，不能用 HTTPS 成功代替语音验收。

本地继续使用 Docker Desktop Kubernetes（kind）；云服务器安装 K3s。二者复用标准工作负载模板，但宿主机网络与入口暴露机制必须分别测试。

本阶段不修改 MiniPay 现有业务 Java、Vue 或 Android 源码，不改变服务的数据所有权和事务边界。经用户授权，只在两个 clean 仓库的 `integrations/yshop-lite` 中精简 YShop 副本，并修正部署、构建、检查脚本和文档；用户执行实际集群切换命令。不自动提交、推送或创建分支。

## 2. 唯一启动入口

- K3s 业务部署：`deploy/k3s/overlays/local` 或 `deploy/k3s/overlays/server`。
- 基础设施 Compose：`deploy/compose-infra/compose.yaml`。
- 本地统一入口：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/k3s/infra.ps1 -Action Start`。详细动作边界见 [中间件操作说明](../deploy/compose-infra/README.md)。
- 根目录旧 Compose：仅作为迁移和回滚参考，不再用于启动业务服务。
- 旧 K8s 草稿：备份在 `.local-backup/k3s-pre-rebuild/20260904-initial-drafts/`，不作为当前配置来源。

严禁同时启动旧 Compose 业务容器和同名 K3s 业务工作负载。

## 3. 组件归属

进入 K3s：

- Identity、Wallet、Payment、Commerce、Agent。
- Consumer BFF、Management BFF、Admin BFF。
- Merchant Web、Ops Web、Admin Web。
- YShop Server、Food H5、YShop Admin Web。

保留在 Compose：

- MiniPay MySQL、MiniPay Redis。
- RabbitMQ、Seata Server。
- YShop MySQL、YShop Redis。
- Coturn。

不进入新启动链路：

- 旧业务 Compose 容器。
- 宿主机业务反向代理配置。
- 演示 callback receiver。

YShop 源仓库中的 `yixiang-drink-open.sql` 含演示数据、历史访问日志和请求内容，只允许用于隔离的本地首次初始化，禁止复制到部署仓库或服务器发布包。服务器必须从切换前生成的受控数据库备份恢复。

## 4. Service 与端口合同

所有 MiniPay Java 容器在 K3s 中统一监听 `8080`。Service 只在集群内部提供稳定名称，Pod 之间不得使用 Pod IP。

| 工作负载 | Service DNS | Service 端口 | 容器端口 |
| --- | --- | ---: | ---: |
| Identity | `identity-service` | 8080 | 8080 |
| Wallet | `wallet-service` | 8080 | 8080 |
| Payment | `payment-service` | 8080 | 8080 |
| Commerce | `commerce-service` | 8080 | 8080 |
| Agent | `agent-service` | 8080 | 8080 |
| Consumer BFF | `consumer-bff` | 8080 | 8080 |
| Management BFF | `management-bff` | 8080 | 8080 |
| Admin BFF | `admin-bff` | 8080 | 8080 |
| Merchant Web | `merchant-web` | 80 | 8080 |
| Ops Web | `ops-web` | 80 | 8080 |
| Admin Web | `admin-web` | 80 | 8080 |
| YShop Server | `yshop-server` | 48080 | 48080 |
| Food H5 | `yshop-food-h5` | 80 | 8080 |
| YShop Admin | `yshop-admin-web` | 80 | 8080 |

内部调用示例：

```text
http://identity-service:8080
http://wallet-service:8080
http://commerce-service:8080
http://yshop-server:48080
```

## 5. Gateway 与 HTTPRoute 合同

以下域名与路径是目标配置合同，尚未经过完整源码路由、OAuth 注册信息和运行请求逐项验收；不能把表中的“其他路径”直接作为 API 域名的公网全路径放行。创建入口前必须枚举 `/internal`、Actuator、Druid、Swagger 等内部/诊断路径并验证公网拒绝。前端菜单、上传和 WebSocket 的实际接口也要加入验收台账。

`Gateway` 负责声明入口端口、协议和证书；`HTTPRoute` 负责根据域名与路径选择 Service；NGINX Gateway Fabric 根据这些对象生成并加载实际 NGINX 配置。业务 Web Pod 中的 Nginx 只负责静态文件和 SPA `try_files`，不是公网总入口。

### 5.1 公共 API

| 域名 | Service |
| --- | --- |
| `identity.su46proj.site` | `identity-service:8080` |
| `payment.su46proj.site` | `payment-service:8080` |
| `wallet.su46proj.site` | `wallet-service:8080` |
| `agent.su46proj.site` | `agent-service:8080` |
| `commerce.su46proj.site` | `commerce-service:8080` |

### 5.2 MiniPay 网页

| 域名 | 路径 | Service |
| --- | --- | --- |
| `merchant.su46proj.site` | `/api`、`/oauth2`、`/login/oauth2`、`/merchant/oauth2`、`/logout`、`/switch-login` | `management-bff:8080` |
| `merchant.su46proj.site` | 其他 | `merchant-web:80` |
| `ops.su46proj.site` | `/identity/*`，移除 `/identity` 前缀 | `identity-service:8080` |
| `ops.su46proj.site` | API、OAuth、登录和退出路径 | `management-bff:8080` |
| `ops.su46proj.site` | 其他 | `ops-web:80` |
| `admin.su46proj.site` | `/identity/*`，移除 `/identity` 前缀 | `identity-service:8080` |
| `admin.su46proj.site` | API、OAuth、登录和退出路径 | `admin-bff:8080` |
| `admin.su46proj.site` | 其他 | `admin-web:80` |

### 5.3 YShop

| 域名 | 路径 | Service |
| --- | --- | --- |
| `food.su46proj.site` | `/app-api`、`/admin-api` | `yshop-server:48080` |
| `food.su46proj.site` | 其他 | `yshop-food-h5:80` |
| `food-admin.su46proj.site` | `/admin-api`、`/infra/ws` | `yshop-server:48080` |
| `food-admin.su46proj.site` | 其他 | `yshop-admin-web:80` |

YShop 管理端构建合同：

```text
VITE_BASE_PATH=/
VITE_BASE_URL=
VITE_API_URL=/admin-api
```

Food H5 构建合同：

```text
API_BASE_URL=https://food.su46proj.site/app-api
```

Druid、Swagger 和 Spring Boot Admin 等诊断页面不向公网暴露。

## 6. OAuth、Android 与身份配置

Identity 对外签发者必须是公网 HTTPS 地址：

```text
IDENTITY_PUBLIC_URL=https://identity.su46proj.site
IDENTITY_ISSUER=https://identity.su46proj.site
```

内部服务读取 JWK 使用：

```text
http://identity-service:8080/oauth2/jwks
```

生产 OAuth 回调：

```text
https://merchant.su46proj.site/merchant/oauth2/code
https://ops.su46proj.site/login/oauth2/code/minipay-ops
https://admin.su46proj.site/login/oauth2/code/minipay-admin
```

所有 Spring 服务启用：

```text
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

Android 地址在 APK 构建时写入，不来自 ConfigMap：

```text
MINIPAY_IDENTITY_BASE_URL=https://identity.su46proj.site
MINIPAY_PAYMENT_BASE_URL=https://payment.su46proj.site
MINIPAY_WALLET_BASE_URL=https://wallet.su46proj.site
MINIPAY_AGENT_BASE_URL=https://agent.su46proj.site
MINIPAY_COMMERCE_BASE_URL=https://commerce.su46proj.site
MINIPAY_FOOD_H5_ORIGIN=https://food.su46proj.site
```

## 7. ConfigMap、Secret 与环境覆盖

ConfigMap 只保存非敏感地址、域名、端口、开关和超时。Secret 保存数据库密码、RabbitMQ 密码、OAuth Secret、服务凭据、YShop HMAC、模型/短信/OSS/邮件凭据。

JWT 私钥和公钥以 Secret 文件挂载，不作为普通环境变量保存。Cloudflare Token 和 Docker Hub Token 使用独立 Secret。

旧 `.env` 只能作为本机导入来源。当前 `import-local-secrets.ps1` 实际仅用于生成全新本地中间件账号密码，尚未实现 OAuth/HMAC/外部供应商凭据导入。已有 `.env.local` 原样保留；`-Force` 被禁用，不能通过重生成环境文件更换已有数据库密码。真实业务配置导入必须另行逐项验证，不能覆盖已有值或复制演示值。

本地覆盖允许：

```text
host.docker.internal
```

服务器跨容器/跨系统连接地址禁止（容器自身的健康检查使用 localhost 是合法的，不做全文件字符串误杀）：

```text
localhost
127.0.0.1
host.docker.internal
旧 Compose 业务容器名
```

服务器 Compose 默认绑定探测到的 Docker bridge 地址；如果实际不是 `172.17.0.1`，必须使用预检得到的真实地址。数据库、Redis、RabbitMQ 和 Seata 端口不得绑定公网接口。

本地当前绑定 `127.0.0.1`；`host.docker.internal` 的解析和端口可达性必须从实际 Pod 验证。失败时调查 Docker Desktop 转发、绑定接口和防火墙，不自动改成 `0.0.0.0`。服务器 bridge 地址也不是跨节点通用地址；当前方案目标为单台服务器，扩展节点需要重新设计中间件可达地址。

Seata 当前固定使用 file 配置/file 注册模式。Payment 与 Wallet 的 `SEATA_SERVER_ADDR` 必须指向 Pod 可达的宿主机 `8091`，两侧事务组映射一致。`SEATA_IP` 是可选的公布地址配置，不能把未设置它直接视为故障。新 Compose 使用 `deploy/compose-infra/seata/application.yml`，不再依赖旧业务部署的 Seata 配置。2.6.0 事务协调器以 `web-application-type: none` 运行，本轮不部署独立控制台，不再将未启动的 7091 控制台端口暴露；旧私有文件的控制台变量保留但不使用。

## 8. Commerce 与 YShop 双向合同

Commerce：

```text
MINIPAY_FOOD_PROVIDER=yshop
YSHOP_INTERNAL_URL=http://yshop-server:48080
```

YShop：

```text
MINIPAY_COMMERCE_INTERNAL_URL=http://commerce-service:8080
```

两侧必须使用相同的 `YSHOP_MINIPAY_HMAC_SECRET`，并连接同一个 RabbitMQ。订单、退款、支付回调地址必须指向集群内 Service 或对应公网 HTTPS 域名，不得指向 Compose 业务名或本机地址。

## 9. 镜像合同

镜像统一使用 Docker Hub 私有仓库和固定版本：

```text
suqihang/<service>:0.1.0-k3s.1
```

共 14 个镜像：8 个 MiniPay Java/BFF、3 个 MiniPay Web 继续使用 `0.1.0-k3s.1`；YShop Server、Food H5、YShop Admin Web 的 Lite 切换使用 `0.1.0-k3s.2-lite.1`。禁止使用 `latest` 或用一个标签覆盖所有镜像。

每个业务组的顺序：源码/配置检查 -> 构建固定版本镜像 -> 镜像冒烟 -> 推送已确认的私有仓库（用户执行）-> 创建拉取凭据 -> 部署该组 -> 运行验收。必须先让集群能取得镜像，再部署工作负载。

中间件现在的 `mysql:8.4`、`redis:7.4-alpine` 是版本系列标签，并非不可变镜像。发布前将经过验证的镜像 digest、应用标签、K3s、Gateway API、NGINX Gateway Fabric、cert-manager 版本记入版本清单并检查兼容性；本地新镜像检查脚本使用的 `python:3.12-alpine` 同样只用于诊断，不是发布镜像锁定的证据。

## 10. HTTPS 合同

- K3s 安装时禁用默认 Traefik。
- 保留 K3s ServiceLB，由 NGINX Gateway Fabric 接管 80/443。
- GatewayClass 固定为 `nginx`。
- 80 只重定向到 443。
- cert-manager 使用 Cloudflare DNS-01 申请 `*.su46proj.site`。
- Gateway 引用显式创建的 `minipay-wildcard-tls` Secret。
- Cloudflare 最终使用 Full (strict)。

## 11. 固定部署顺序

1. 整理配置、保留旧草稿备份与旧部署回滚入口。
2. 完成中间件静态检查、Compose 启动、账号/表结构检查与 Pod 跨边界连接；补备份恢复证据。
3. 核对所有业务变量，创建基础 ConfigMap 与逐服务 Secret；本地中间件密码从同一个私有来源读取。
4. 构建并提供 Identity 镜像，部署 Identity，验证数据库/Redis/issuer/JWK。这里只接受内部 API，尚不宣称浏览器登录完成。
5. 构建 Management BFF 与 Merchant Web 镜像，部署第一条网页链，配置 Gateway/HTTPRoute 与本地入口、OAuth 回调，实现第一条完整登录链。
6. 构建并部署 Wallet/Payment，验证 Seata TCC、钱包/支付与 RabbitMQ 事件。
7. 构建并部署 Commerce、YShop Server/Food H5/YShop Admin，验证 HMAC、授权/绑定、下单、支付结果与状态回写。
8. 构建并部署 Agent、Consumer BFF、Admin BFF、Ops Web、Admin Web，完成其余页面/API、权限、SSE/WebSocket 验收。
9. 用显式本地测试地址构建 Android，验证实际设备/模拟器链路；生产 APK 使用生产地址另行构建和验收。
10. 完成本地全矩阵、持久化/备份恢复、失败回滚测试，锁定镜像与组件版本。
11. 服务器扩容和备份、安装 K3s（禁用 Traefik）、恢复数据与适配既有数据库用户密码、部署固定镜像、DNS-01 发证、切换入口并全矩阵复测。

任何一步失败都停在当前步骤，不跳过依赖继续部署。每组给出“目的 -> 本组文件 -> 一条阶段命令 -> 通过/失败/待验证”，机械检查交给可读脚本，失败才展开单独排查。不再为一个查询拆多轮聊天或每条命令后强制口头考试。

本地 HTTPRoute 能访问不能代替 HTTPS/OAuth：浏览器本地 DNS/Host、Cookie Secure、issuer、OAuth 回调、Android 网络和 Food H5 构建地址必须配成同一套本地测试入口。保留生产域名合同；本地测试覆盖通过构建参数和 overlay 指定，不让本地 App 偷连线上后端。

## 12. 每个工作负载的验收门槛

- Deployment 达到期望副本并全部 Ready。
- startup、readiness、liveness probe 行为正确。
- EndpointSlice 至少有一个 `ready: true` 端点。
- Service DNS 可解析，端口连接正确。
- 日志中无数据库、MQ、Seata、OAuth、issuer、HMAC 或密钥错误。
- Gateway 为 `Programmed=True`。
- HTTPRoute 为 `Accepted=True`、`ResolvedRefs=True`。
- 镜像标签固定且不存在 `latest`。
- Server overlay 不包含本机地址或明文 Secret。

## 13. 页面与业务验收矩阵

MiniPay Web：merchant、ops、admin 分别验证登录、首页、所有菜单、浏览器刷新、静态资源、API、OAuth 回调、退出登录和权限拒绝。

YShop Admin Lite：验证登录、允许列表动态菜单、门店、商品、分类/SKU、订单履约、整单退款、员工角色、上传和操作日志。WebSocket 与被移除的诊断/营销/微信页面不得进入运行时或公网路由。

Android：验证登录、身份授权、钱包、支付、Agent、Commerce 和 Food H5 跳转。

外卖硬性链路：

```text
Android
 -> Commerce 创建 MiniPay 外卖订单
 -> YShop 创建对应订单
 -> RabbitMQ 事件
 -> Payment 创建支付单
 -> 支付结果回调
 -> Commerce 与 YShop 最终状态一致
```

每个失败必须保存请求时间、requestId/业务单号、脱敏后的相关服务日志、HTTP 状态和关联配置项；只修复被证据证明错误的部署配置。如果证据指向业务源码缺陷，记录并报告阻塞，不以伪造通过、关闭鉴权或吞掉错误实现“页面正常”。

Android 的 `gradle.production.properties` 不会仅因文件存在就自动被 Gradle 读取。当前源码使用 `providers.gradleProperty("MINIPAY_...")`；后续构建脚本必须显式加载私有文件并传入属性。协议/隐私链接、地图 Key、签名、包名与 SHA 指纹也要验收，不能仅填六个后端 URL。

## 14. 云服务器切换与回滚

服务器完整迁移前按本项目约定扩容至至少 4 核、8GB 内存、80GB 磁盘，这是本项目的容量门槛，不是 K3s 的通用最低配置，也不是容量压测通过的保证。切换前备份数据库、Docker volumes、旧 `.env`、宿主机 Nginx 和证书。

本地和服务器共用 base 和已验证镜像，通过环境覆盖区分网络、TLS、凭据与数据。不能把 Windows SQL 绝对路径、全新随机数据库密码或 Docker Desktop 数据卷原样当作服务器迁移。已有数据的 HMAC/加密密钥要保留正确历史来源；数据库恢复后验证目标应用用户授权和密码。

先通过服务器本机端口转发和 Host Header 验证 Gateway 路由；DNS-01 证书签发成功后，才停止旧宿主机 Nginx，让 K3s 接管 80/443，再切换 Cloudflare DNS。

若 HTTPS、OAuth、支付或外卖任一关键链路失败：恢复旧 DNS -> 停止新公网入口 -> 恢复宿主机 Nginx -> 恢复旧 Compose 业务容器。不得删除任何新旧数据库或备份。

## 15. 当前完成状态

- [x] 旧未提交 K8s 草稿已本地备份。
- [x] 新目录和文件壳已规划。
- [x] 目标服务、端口、域名与路由草案已记录；源码逐路由与真实页面验收仍待完成。
- [x] 基础设施 Compose、本地初始化文件与统一阶段脚本已填写。
- [x] 用户历史输出显示 MiniPay MySQL Healthy 与六库初始化日志完成（非 Pod 连接证据）。
- [x] 当前六个常驻中间件健康、实际账号和表结构、诊断 Pod 连接验收。
- [ ] Coturn 语音链路与数据备份/恢复。
- [x] 本地 ConfigMap 与逐服务 Secret 已应用，真实值未写入文档或镜像。
- [x] 14 个 Deployment/Service 与健康检查已通过本地运行验收。
- [x] Gateway 和 10 个本地 HTTPRoute 已通过 Programmed/Accepted/ResolvedRefs 验收；服务器证书/HTTPS仍待验。
- [ ] Docker Hub 镜像。
- [ ] 本地页面和业务验收。
- [ ] 云服务器迁移与验收。

当前下一步：以已通过的本地技术平台为底座，执行 Identity 登录/JWT、Wallet 查询、Payment/Wallet TCC、Commerce/YShop/RabbitMQ、BFF OAuth/Cookie、Agent SSE、逐页与 Android 业务矩阵；再做隔离的数据重建和备份恢复。全部通过后才进入服务器阶段。

## 16. 本次修正依据

- [NGINX Gateway Fabric 架构](https://docs.nginx.com/nginx-gateway-fabric/overview/gateway-architecture/)：区分规则、控制器和处理请求的 NGINX。
- [K3s 网络服务](https://docs.k3s.io/networking/networking-services)：ServiceLB、Traefik 与宿主机端口占用。
- [Seata 2.6.0 配置示例](https://github.com/apache/incubator-seata/blob/v2.6.0/server/src/main/resources/application.example.yml)：file 模式、8091、无 Web 应用的协调器。
- 当前 `services/payment-service/src/main/resources/application.yml` 与 Wallet 同名配置：`SEATA_SERVER_ADDR` 和 `registry.type=file`。
