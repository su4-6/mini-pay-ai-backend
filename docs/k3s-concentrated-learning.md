# MiniPay K3s 全链路集中学习

本材料只在本地全链路验收通过后开始。届时所有概念都对照真实 Pod、Service、EndpointSlice、日志和配置，不靠背名词。

## 学习顺序

### 1. 一张总图

```text
浏览器/Android
  -> Gateway 入口
  -> HTTPRoute 选路
  -> Service 稳定名称
  -> EndpointSlice 中的 Ready Pod
  -> Java/BFF/Nginx 容器

Java Pod
  -> Docker Desktop 宿主可达地址
  -> Compose 端口
  -> MySQL / Redis / RabbitMQ / Seata
```

构建链：

```text
源码 -> 测试 -> JAR/静态文件 -> Dockerfile -> 镜像
镜像 + ConfigMap + Secret -> Pod
Deployment 管 Pod -> Service 找 Ready Pod -> Gateway 对外入口
```

### 2. Wallet 样板逐层追踪

1. 源码经 Maven 测试后产生 Wallet JAR。
2. Dockerfile 把 JAR 放入固定 Java 21 运行环境，形成不可写的应用镜像。
3. Deployment 指定镜像、副本、三类探针、安全上下文和资源限制。
4. ConfigMap 提供地址、端口、issuer；Secret 提供数据库/MQ/服务凭据。
5. `MYSQL_URL` 明确来自 `WALLET_MYSQL_URL`，防止误连 Identity/Payment 数据库。
6. Service 通过标签选择 Ready Wallet Pod；EndpointSlice 保存实际端点。
7. Wallet 通过宿主可达地址访问 Compose MySQL/RabbitMQ/Seata，通过 Service DNS 读取 Identity JWK。

### 3. 六条真实请求/事务链

1. Identity：登录 -> 校验 -> 签发 JWT -> 其他服务读取 JWK 验签。
2. Wallet：HTTP -> Controller -> Application -> Domain -> Mapper/MySQL -> 余额与复式账本 -> 响应。
3. Payment/Wallet：Payment 编排 -> Seata TCC Try -> Confirm/Cancel；复测幂等、空回滚、防悬挂。
4. Commerce/YShop：订单本地事务 + Outbox -> RabbitMQ -> Inbox/幂等 -> 最终状态一致。
5. Web/BFF：网页 -> BFF -> Identity OAuth -> 回调 -> Redis Session -> Secure/HttpOnly Cookie。
6. Agent：HTTP 创建 run -> 模型适配器/白名单工具 -> 下游 Service -> SSE 持续返回事件。

### 4. 服务对比

| 类型 | 共同部署骨架 | 必须隔离或独有的部分 |
|---|---|---|
| Identity | Deployment、Pod、Service、探针 | Identity 库、JWT 私钥、公钥、OAuth 客户端、Redis |
| Wallet | 同一 Java 骨架 | Wallet 库、余额/冻结/复式账本、TCC 分支 |
| Payment | 同一 Java 骨架 | Payment 库、支付编排、TCC 协调、Outbox、通道密钥 |
| Commerce/YShop | Pod、Service、MQ连接 | 各自数据库；共享同一 HMAC；RabbitMQ 最终一致性 |
| BFF | Java Pod、Service、Redis | OAuth 回调、Cookie 名称、会话命名空间、下游聚合 |
| Agent | Java Pod、Service、数据库/MQ | SSE、模型开关、模型密钥、白名单工具、TURN |
| Web | 镜像、Pod、Service、探针 | 静态文件和 SPA 回退；无 JVM、无业务数据库 |

重复模板包括标签、探针、安全上下文、固定镜像和 Service 选择器；绝不能照抄的包括数据库 URL、Secret、OAuth 回调、Cookie、HMAC、issuer 和下游地址。

### 5. 固定故障定位顺序

```text
域名/Host
-> Gateway Programmed
-> HTTPRoute Accepted/ResolvedRefs
-> Service 端口/选择器
-> EndpointSlice Ready 端点
-> Pod 状态与重启原因
-> startup/readiness/liveness
-> ConfigMap/Secret 引用
-> MySQL/Redis/RabbitMQ/Seata
-> Java 业务日志与 requestId
```

快速判断：`404` 先看 Host/Route/路径；`503` 先看 Service 与 EndpointSlice；`CrashLoopBackOff` 先看 Pod events、上一轮日志、配置和依赖认证；数据库认证错误先核对“服务自己的 URL 键 + 自己的账号”，不直接改库密码。

## 学习验收

- 不看资料画出两条总链路。
- 用一个真实请求指出每一层对应的资源和日志。
- 解释镜像、容器、Pod、Deployment、Service、Gateway 的差异。
- 解释 ConfigMap/Secret 在容器启动时如何形成环境变量或挂载文件。
- 对比 Identity、Wallet、Payment 的相同部署骨架与不同业务边界。
- 对 `404`、`503`、`CrashLoopBackOff`、数据库认证失败各说出前三个检查点。

本地复盘完成后再补“本地与生产差异”：镜像 digest、私有仓库、HTTPS/证书、生产 issuer/OAuth、服务器中间件地址、DNS 切换、备份与回滚，不重复整套基础课。
