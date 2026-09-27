# MiniPay Kubernetes / K3s 部署入口

最终架构：**K3s 管业务与入口，Compose 管中间件**。
当前线上环境是腾讯云单节点（4 vCPU / 4 GB / Ubuntu 24.04），overlay 使用 `server-lite`。

```
浏览器/App → Cloudflare（DNS、TLS、边缘缓存、Worker 落地页）
           → K3s ServiceLB（80/443）
           → NGINX Gateway Fabric（控制面 + 数据面）
           → 11 条 HTTPRoute → 业务 Pod（8 个 Java 服务 + 3 个 Web 静态 + 维护页）
           → 宿主机 Docker Compose 中间件（MySQL×2 / Redis×2 / RabbitMQ / Seata）
```

- 网关：**NGINX Gateway Fabric 2.7.0** + Gateway API v1.6.1，`GatewayClass=nginx`，
  Gateway `minipay-gateway`（listeners `http:80` + `https:443`，TLS 由 Cloudflare Origin CA 证书提供）。
- 不再使用仓库早期的单机静态 nginx 反代（`deploy/nginx/`）与「三仓库 Compose 生产栈」
  （`compose.production.yml` / `compose.edge.yml` / `scripts/deploy-production-stack.sh` 等）；
  这些文件已随 K3s 迁移删除，需要时从 Git 历史取回。

## 目录与 overlay

| 路径 | 作用 |
| --- | --- |
| `base/` | 全部工作负载、路由、ConfigMap；`workloads/*.yaml` 一个 Deployment+Service 一份 |
| `base/routes/` | `minipay-web-routes.yaml`（前端与 BFF/identity 分流）、`public-api-routes.yaml`、`yshop-routes.yaml` |
| `base/config/nginx-proxy.yaml` | **NginxProxy**：声明 NGF 数据面 resources（NGF reconcile 不会覆盖） |
| `base/workloads/maintenance-page.yaml` | 外卖「维护中」静态页工作负载 |
| `overlays/local/` | 本地 K8s（`*.minipay.localhost`） |
| `overlays/server/` | 线上：真实域名、Cloudflare Origin CA、外部中间件地址、私密 env（不进 Git） |
| `overlays/server-lite/` | 在 `server` 之上做**内存瘦身**：limits 400Mi（yshop 768Mi）、JVM 参数、`maxSurge=0` |
| `overlays/server/private/` | Secret 与 digest 锁定组件（**不进 Git**：`identity.env`、`payment.env`、`jwt-*.pem`、`digests/`） |
| `generated/image-digests.json` | 已推送镜像的 tag → digest 记录 |

## 应用清单（改完清单后）

```bash
cd <repo root>
k3s kubectl kustomize deploy/k3s/overlays/server-lite | kubectl apply --server-side --force-conflicts -f -

# NGF 控制面资源/滚动策略（数据面 resources 已在 NginxProxy 里，无需重复）
sudo bash scripts/k3s/apply-ngf-tuning.sh
```

> 首次在服务器上准备环境（生成私密配置、runtime.env、应用清单）用
> `scripts/k3s/bootstrap-server.ps1 -InfraEnvFile deploy/compose-infra/.env.local -ReuseCryptoFrom <overlays/server/private>`；
> 它不会覆盖既有 JWT 密钥对，重跑前请先备份 `private/jwt-*.pem`。

## 镜像：构建 / 推送 / 拉取

```powershell
# 构建（示例：后端服务与前端静态镜像）
mvnw.cmd -B -q -pl services/<svc> -am package -DskipTests
docker build -f docker/k3s-service.Dockerfile --build-arg MODULE=<svc> -t suqihang/<svc>:<tag> services
docker build -f <frontend>/docker/k3s-web.Dockerfile --build-arg APP=ops-web -t suqihang/ops-web:<tag> <frontend>
docker push suqihang/<svc>:<tag>
```

- 推送后把新 digest 写进 `generated/image-digests.json` 与 `overlays/server/private/digests/kustomization.yaml`，
  再按上面的命令 apply（清单按 digest 锁定，可复现）。
- **纯内网/受限网络兜底**：若服务器无法从 Docker Hub 拉新 digest（被 DNS 劫持时会出现
  `dial tcp: lookup registry-1.docker.io: no such host`），可改用

  ```bash
  docker save suqihang/<img>:<tag> -o img.tar
  scp img.tar <server>:/tmp/
  ssh <server> 'sudo k3s ctr -n k8s.io images import /tmp/img.tar'   # 注意 -n k8s.io
  ```

  并在 `overlays/server/kustomization.yaml` 里用 tag + `imagePullPolicy: IfNotPresent` 覆盖 digest 引用。
  网络恢复后删掉该 patch 即回到 digest 锁定。

## 外卖（YShop）栈的下线与恢复

外卖模块（`yshop-server` / `yshop-food-h5` / `yshop-admin-web`）在内存紧张时整体下线，
两个域名由维护页顶替（不再返回 503）：

```bash
# 下线（server overlay 里已有 replicas=0 的 patch，这里是对运行中的集群直接生效）
kubectl -n minipay scale deploy yshop-server yshop-food-h5 yshop-admin-web --replicas=0
kubectl -n minipay apply -f <render>            # 路由：/ 规则指向 maintenance-page（见 base/routes/yshop-routes.yaml）

# 恢复
kubectl -n minipay scale deploy yshop-server yshop-food-h5 yshop-admin-web --replicas=1
# 并把 base/routes/yshop-routes.yaml 中 / 规则的 backendRefs 改回
#   yshop-food-h5 / yshop-admin-web，然后重新 apply
```

维护页镜像（内容为 `maintenance/index.html` + 页脚用的 `maintenance/beian.png`，公安备案图标）：

```bash
docker build -f deploy/k3s/maintenance/Dockerfile -t suqihang/maintenance-page:0.1.1 deploy/k3s/maintenance
# 节点上直接构建 + 导入（Docker Hub 域名被劫持时用这条，改动 index.html 后无需外部网络）
docker save suqihang/maintenance-page:0.1.1 | sudo k3s ctr -n k8s.io images import -
kubectl -n minipay set image deploy/maintenance-page web=suqihang/maintenance-page:0.1.1
kubectl -n minipay rollout status deploy/maintenance-page
```

## 演示数据与账号

```bash
# 服务器演示账号（运营/商户/系统管理员）绑定与角色对齐，幂等
docker exec -e MYSQL_PWD=<pwd> -i minipay-infra-minipay-mysql-1 \
  mysql -uroot --default-character-set=utf8mb4 minipay_identity < scripts/k3s/sql/provision-server-demo-accounts.sql
```

演示账号（密码 `MiniPay@123456`）：运营 `13800138000`、商户 `13900000009`（角色必须是 `merchant_owner`，
否则管理端「商户所有人」计数为 0）、系统管理员 `13800138002`；App 短信固定 `123456`。

## 常用运维与验收命令

```bash
kubectl -n minipay get pods -o wide
kubectl -n minipay get httproute; kubectl -n minipay get gateway
kubectl -n nginx-gateway get pods
kubectl get --raw='/readyz?verbose' | grep -E 'etcd|readyz'
free -m; uptime

# 逐页验收（本地脚本，不入库）
node _codex_digest/accept/cdp-perf.mjs https://ops.su46proj.site/ops/login ops-login
node _codex_digest/accept/cdp-portal-sweep.mjs sweep ops
node _codex_digest/accept/cdp-portal-sweep.mjs textscan ops
node _codex_digest/accept/cdp-portal-sweep.mjs upload merchant
```

## 内存与容量注意事项（单节点）

- 物理内存 3.7 GB，而全部 Deployment 的 memory limits 合计约 5.9 GB：**必须靠 `server-lite` 与下线非必要负载控制峰值**，
  否则节点会持续换页 → etcd 健康检查失败 → 网关控制面（NGF）崩溃循环 → 数据面无法下发配置。
  推荐把服务器内存升到 8 GB。
- 滚动更新策略：业务 Deployment 用 `maxSurge=0/maxUnavailable=1`（先停后起，避免内存峰值翻倍）；
  NGF 数据面保持 NGF 默认（单副本下等价于先起新后停旧），**不要**给数据面设 `maxSurge=0`——
  控制面不可用时会把唯一就绪的网关 Pod 缩掉，导致全站 521。

## 两个已踩过的基础设施坑（换机器/重建中间件时必查）

### 1) Seata 的表不会自动补（TCC 转账直接 503）

`docker/mysql/core/init/002-seata-schema.sql` 只在 MySQL **首次初始化**时执行
（docker-entrypoint-initdb.d）。若数据卷早于该挂载存在，`seata` 库里就没有
`global_table / branch_table / lock_table / distributed_lock / vgroup_table`，表现为：

- 转账确认接口返回 `503 DISTRIBUTED_TRANSACTION_UNAVAILABLE`
- payment-service 日志：`TransactionException[begin global request failed. msg=Table 'seata.global_table' doesn't exist]`

补齐（用 seata 自己的库用户，从 compose 网络里的临时容器连，避开 `'seata'@'localhost'` 授权问题）：

```bash
docker run --rm -i --network minipay-infra mysql:8.4 \
  mysql -h minipay-mysql -useata -p"$(docker exec minipay-infra-seata-server-1 printenv SEATA_DB_PASSWORD)" \
  --default-character-set=utf8mb4 seata < docker/mysql/core/init/002-seata-schema.sql
```

### 2) 内部 OAuth 客户端密钥会漂移（外卖授权/Agent 工具调用报“资料暂不可用”）

identity 启动时会用**自己那份** secret 重新注册所有内部客户端，所以 identity 侧是权威值。
如果某个消费方（wallet / agent / commerce）secret 里的同名 key 与之不同（或干脆缺失），
客户端拿到的是 `invalid_client`，业务侧表现为：

- `COMMERCE_IDENTITY_UNAVAILABLE`「用户授权资料暂不可用」（外卖授权、外卖入口 SYNC_FAILED）
- agent 侧工具调用/委派授权失败

巡检与修复（把 identity 的值同步到消费方并滚动重启）：

```bash
# 巡检：逐个内部客户端比较两侧 sha256 前缀
for k in PAYMENT_TO_IDENTITY_CLIENT_SECRET PAYMENT_TO_WALLET_CLIENT_SECRET \
         WALLET_TO_IDENTITY_CLIENT_SECRET AGENT_TO_PAYMENT_CLIENT_SECRET \
         AGENT_TO_IDENTITY_CLIENT_SECRET AGENT_DELEGATION_CLIENT_SECRET \
         IDENTITY_TO_PAYMENT_CLIENT_SECRET COMMERCE_TO_IDENTITY_CLIENT_SECRET; do
  echo "$k"
done   # 具体比对脚本见 _codex_digest/accept/fix-client-secret-drift.sh

# 修复：kubectl patch secret <消费方 secret> --type merge -p '{"data":{"<KEY>":"<identity 的 base64 值>"}}'
#       然后 kubectl -n rollout restart deploy <消费方>
```

## 边缘与 CDN（两套并存，**变更 #60/#61**）

| 流量 | 走哪 | 备注 |
| --- | --- | --- |
| 页面（`su46proj.site` / `www` / `pay`） | Cloudflare Worker | 边缘直出，实测 TTFB ~215 ms |
| 控制台（`ops` / `merchant` / `admin`） | Cloudflare → K3s | 静态外壳由 Worker 做**边缘缓存**（`x-minipay-edge: HIT/MISS/BYPASS`，TTL 600 s，浏览器侧强制 `no-cache`） |
| 控制台接口（BFF） | Cloudflare → K3s | 动态不缓存，国内 TTFB 1.2–1.6 s |
| 消费者 H5（`app`） | Cloudflare → K3s | 根路径构建；`/api/**` 走 consumer-bff，其余走 consumer-web |

> **APK 下载已下线**（消费者端从 Android App 换成 H5）：腾讯云 CDN `dl.su46proj.site`（域名已删除，
> 12 → 11）、R2 桶 `minipay-downloads` 与自定义域名 `download.su46proj.site`、DNS 记录与相关缓存
> 均已清理（2026-09-27）；源站服务器上的宿主 nginx 下载 vhost、`/var/www/**/downloads`（约 1.03 GB）、
> 服务器上的 `android/` 源码副本也一并删除（根分区可用 5.4 G → 6.4 G）。两个 Worker 重新上传后
> R2 绑定为空、路由不变。下线原因、恢复步骤与「删 APK 不释放内存」的说明见前端仓库 `android/RETIRED.md`，
> 执行明细见 `integrations/cloudflare/pay-edge-legacy/R2-DEPLOYMENT.md`。

运维要点：

```bash
# ① 前端发新版后：刷新控制台外壳的边缘缓存（否则最多 10 分钟边缘仍供旧 HTML）
curl -X POST "https://api.cloudflare.com/client/v4/zones/<zone>/purge_cache" \
  -H "Authorization: Bearer <CF token>" -H 'Content-Type: application/json' \
  --data '{"files":["https://ops.su46proj.site/ops/login","https://merchant.su46proj.site/merchant/login","https://admin.su46proj.site/","https://app.su46proj.site/"]}'

# ② 腾讯云 CDN 只剩「拆除收尾」，不再是线上依赖：
#    确认 dl.su46proj.site 已停用/删除、证书已过期不再续期。
#    （Android 下线前这条命令是用来刷 APK 缓存的，现在别再按那个用途用。）
node _codex_digest/accept/tencent-cdn.mjs list | describe dl.su46proj.site
```

> 密钥：Cloudflare token 在 `C:\minipay-keys\cf-token.txt`，腾讯云密钥在 `C:\minipay-keys\tencent-cloud.txt`，均不进 Git。
> 账号里还留着两个**已关闭的旧 CDN 域名**（`www.su46proj.site` / `su46proj.site`，境外节点、7 月创建），
> 与 Worker 的页面路由冲突，建议删除。

## 内存收敛与 C 端切换（消费者 H5 取代 Android App）

节点总内存 **3723 MiB**，K3s 与宿主机中间件共用。切换 C 端时按"先加后减"的顺序操作，
任何一步都要先确认新入口可用，再执行下一步。

### 内存账（本次调整前后）

| 组件 | 位置 | 内存 request / limit | 处置 |
| --- | --- | --- | --- |
| `commerce-service` | K3s | 384Mi / 1Gi | **缩到 0**：点餐外卖后端，外卖栈已下线 |
| `agent-service` | K3s | 512Mi / 1280Mi | **缩到 0**：由 `miling-service` 取代 |
| `miling-service` | K3s | 320Mi / 768Mi | **新增**：米灵新实现（脚手架） |
| `consumer-web` | K3s | 32Mi / 128Mi | **新增**：消费者 H5 静态站 |
| `yshop-mysql` | 宿主机 | ≈250–300Mi | **从服务器面排除**（`profiles: !override ["food-disabled"]`） |
| `yshop-redis` | 宿主机 | ≈30–50Mi | 同上 |
| `coturn` | 宿主机 | ≈30Mi | 只在 `voice` profile 下启动，服务器面不启用 |
| APK + 腾讯云 CDN + R2 | 集群外 | **0Mi** | 省 40MB 磁盘与下行流量，**不释放内存** |

净效果约为 **回收 700 MiB 左右、新增约 350 MiB**，实际解压出 300–400 MiB。

### ✅ 2026-09-27 已完成线上落地（实际部署状态 + 踩过的坑）

C 端已从 Android App 切到消费者 H5，服务器实况：

| 工作负载 | 镜像 | 说明 |
| --- | --- | --- |
| `consumer-bff` | `suqihang/consumer-bff:0.1.0-k3s.13` | 会话/代理/SSE + 扫码付款三个端点 |
| `identity-service` | `suqihang/identity-service:0.1.0-k3s.12` | 含 `minipay-consumer-bff` OAuth 客户端注册（scope 含 `payment.order.write`） |
| `miling-service` | `suqihang/miling-service:0.1.0-k3s.3` | 米灵（AI 助手），模型走**小米 MiMo** |
| `consumer-web` | `suqihang/consumer-web:0.1.0-k3s.2` | 消费者 H5 静态站 |
| `agent-service` / `commerce-service` / `yshop-*` | — | `replicas: 0`（腾内存） |
| 宿主机 `yshop-mysql` / `yshop-redis` | — | 已 `docker stop`（数据卷保留） |

内存实况（`kubectl top pods`，切换前 → 切换后）：agent 280Mi + commerce 213Mi 释放，
新增米灵约 300Mi + consumer-web 约 4Mi，**净释放约 190Mi 实际占用**；
宿主机可用内存从 336Mi 升到 ~900Mi（切换瞬间），长期稳定在 400Mi+。

**平台侧验收（2026-09-27 实跑，全绿）**：19 个 Deployment 期望副本数全部吻合（含 5 个刻意缩到 0 的）、
5 个缩容项**零 Ready 端点**、12 个健康探针全部 UP/ok（米灵走聚合健康、含 Redis）、
Gateway `Programmed=True`、14 条 HTTPRoute、`consumer-web` 路由指向 `app.su46proj.site`、
`app.su46proj.site` 与 `pay.su46proj.site` 源站均 200。

> ⚠️ Git ↔ 集群的一处已知差异：集群里还有 `landing-pay` / `landing-personal` 两个 Deployment/Service/
> HTTPRoute（各 2 副本，由 `maintenance-page:0.1.1` + ConfigMap `index.html` 提供 `pay./su46proj.site`
> 的落地页），**它们不在本仓清单里**（由仓外的工具生成落地页 HTML 后 apply）。本次只更新了它们的
> ConfigMap 内容（去掉 APK 入口）。`kubectl apply -k` 不会删掉清单外的对象，所以不冲突；
> 但要知道"线上落地页来自这两个不在仓里的工作负载"。
>
> 落地页 HTML 的**生成与应用现在已收进仓库**：`scripts/k3s/apply-landing-pages.ps1`
> （从 `integrations/cloudflare/landing-worker/src/pages.js` 渲染 → 写 `deploy/k3s/landing/*.html`
> → 更新两个 ConfigMap → 重启 Deployment → 用 `-NodeIp` 校验源站）。产物与线上 ConfigMap
> 逐字节一致（MD5 `10e85fe0…` / `0fc77903…`），脚本幂等。详见 `deploy/k3s/landing/README.md`。

上线时用 curl 走通并留档的验收（都在 `https://app.su46proj.site/` 上）：

- 短信登录 → `{authenticated:true, payPasswordSet:true, onboardingRequired:false, realNameStatus:VERIFIED}`
- 钱包余额 `1007270` 分 → 账单列表 → 个人收款码 `minipay://collect/personal?token=…`
- **米灵对话逐字流式**：`event:message.delta` 两帧 + `message.completed(SUCCEEDED)` + `stream.completed`，
  回答内容由 MiMo 生成并落库（`GET /api/v1/ai/conversations/{id}/messages` 可见）
- **转账全链路**：prepare（`minipay://friend/<MiniPay号>` 收款人）→ confirm（支付密码）→ `SUCCEEDED`
  → 余额 `1007270 → 1007170`、账单新增 `TRANSFER/EXPENSE/100分/source=FORM`
- 付款扫码：个人码 → 上游 `422 SELF_COLLECTION_CODE`；伪造商户 token → `404 COLLECTION_CODE_NOT_FOUND`；
  prepare 缺 `resolutionId` → BFF `400 VALIDATION_FAILED`
- **扫码付款（真实商户码）全链路**：识码 `MERCHANT_COLLECTION`（星河便利店）→ 创建支付单
  `P01A0E28959727F50B19F771D42893604` → 支付密码确认 `SUCCEEDED` → 余额 `1000000 → 999900` 分，
  账单新增 `MERCHANT_PAYMENT / EXPENSE / 100 分 / counterpartyDisplay=星河便利店`

### 怎么在演示环境验证「扫商户码付款」（含怎么拿到商户码）

H5 用户侧流程：`钱包 → 扫码付款 → 粘贴商户收款码内容 → 识别 → 输入金额 → 支付密码 → 确认`。
收款码内容形如 `minipay://collect/merchant?token=mc_…`，来自**商户端**（`merchant.su46proj.site` →
收款码页），或直接用商户 API 取（需要 merchant-api token）：

```bash
# 1) 拿 merchant-api token（商户端是公共客户端 + PKCE；登录要图形验证码）
#    ⚠️ 演示环境可绕：验证码答案在 Redis 里只存 HmacSHA256(pepper, CODE)，
#       字母表 32 种、码长 4 → 32^4 可穷举（`CAPTCHA_PEPPER` 见 identity-runtime Secret，
#       key 为 minipay:auth:captcha:<id>，用 redis 容器的 $MINIPAY_REDIS_PASSWORD 读）
#    POST identity /api/v1/auth/captchas                          -> captchaId
#    GET  Redis  minipay:auth:captcha:<captchaId>                 -> digest
#    (python 穷举 4 位码) 
#    POST identity /api/v1/auth/merchant/password/verify          -> authorizationCode
#         {mobile,password:MiniPay@123456,captchaId,captchaCode,
#          clientId:minipay-merchant-bff,redirectUri:https://merchant.su46proj.site/merchant/oauth2/code,
#          codeChallenge:<S256>,codeChallengeMethod:S256,deviceId}
#    POST identity /oauth2/token (grant_type=authorization_code,client_id=minipay-merchant-bff,
#         code,redirect_uri,code_verifier)                          -> access_token (aud=merchant-api)
# 2) 取收款码
#    GET payment /api/v1/merchant/merchants/{merchantId}/collection-codes
#         -> [{application:{...}, collectionCode:{token, qrContent:"minipay://collect/merchant?token=..."}}]
# 3) 付款人要求：已完成实名、且【不是该商户的 owner】（上游会返回 SELF_MERCHANT_PAYMENT）、
#    并有支付密码（没有可用 H5 的「设置支付密码」先设 6 位数字）。
# 4) 跑验收脚本（会真正扣 1 分）：
pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -Mobile <付款人手机号> \
     -PayPassword 123456 -MerchantQr 'minipay://collect/merchant?token=mc_…'
```

⚠️ 上线过程中实测发现并修掉的 4 个契约/接口缺陷（本地单测都没覆盖到，只有真机调用才暴露）：

1. `HttpConsumerApiGateway` 用 `exchangeToMono(Mono::just)` 之后才读 body → **上游响应体恒为空**
   （余额/账单/AI 列表全 502 或空 JSON）。改成在 `exchangeToMono` 内部读完 status/ct/body。
2. BFF → 米灵发消息时字段写成 `message`，上游 trigger 端要 `content` →
   `400 MILING_REQUEST_INVALID: content 不能为空`。**以后端 Controller 的 DTO 为准**。
3. 转账 `source` 写成 `"H5"`，上游只接受 `FORM | AI | PERSONAL_COLLECTION_CODE` →
   `400 INVALID_TRANSFER_SOURCE`。H5 转账表单就是 `FORM`。
4. 设置支付密码后不换访问令牌：`pay_password_set` 是签发时写进 JWT 的 claim，旧令牌一直是 `false`，
   随后转账被 identity 判成 `403 PAYMENT_PASSWORD_REQUIRED`。现在设置成功后会 best-effort 刷新一次令牌，
   并同步刷新会话快照（否则前端一直显示「尚未设置支付密码」）。
5. **`consumer-bff` OAuth 客户端少了 `payment.order.write`**：payment-service 对
   `POST /api/v1/payment-orders/**`（扫码付款的创建/确认支付单）要求
   `consumer-api + payment.order.write`，而客户端只注册了 `payment.order.read` →
   线上表现为 `403 INSUFFICIENT_SCOPE`，**H5 的「扫码付款」永远走不完**。
   现在 scope 清单已补齐（identity-service `CONSUMER_BFF_SCOPES`），并有测试锁定。

⚠️ 两个构建/运维坑（下次务必照做）：

- `docker build` 默认带 attestation manifest，`k3s ctr images import` 进去后 kubelet 报
  **`image can't be pulled`**；必须 `docker build --provenance=false --sbom=false` 出单平台镜像。
- 米灵镜像**不要**在 Dockerfile 里 `apk add tzdata curl`（上海节点到 Alpine CDN 会卡死）：
  K8s 探针走 httpGet，时区由 `JAVA_OPTS -Duser.timezone` + `TZ` 决定，精简 Dockerfile 即可。

⚠️ 线上落地页（`pay.su46proj.site` / `su46proj.site`）之前由**集群内 `landing-pay`/`landing-personal`
Deployment + ConfigMap** 提供，不是 Cloudflare Worker —— 改落地页要改 ConfigMap 并重启 Deployment。
本次已把两个 ConfigMap 换成"无 APK 入口、指向消费者 H5"的版本，并**刷完腾讯云 CDN 缓存**：
实测公网 `pay.su46proj.site` = 42025 字节新页（`apk_refs=0`、7 处 H5 入口）、`su46proj.site` 与 `www`
= 34701 字节新首页（与仓库产物 MD5 一致）。

⚠️ APK 分发入口现状（2026-09-27 收尾后：**已全部消失**）：
腾讯云 CDN 域名 `dl.su46proj.site` 已删除；Cloudflare 侧 R2 桶 `minipay-downloads`（含 4 个 APK 对象）、
R2 自定义域名 `download.su46proj.site`、DNS 记录 `download`/`dl`、两个 Worker 上的 R2 绑定都已删除或清空；
源站服务器上的宿主 nginx 下载 vhost（`minipay-direct-download.conf`、两处 `/downloads/` location）、
`/var/www/pay.su46proj.site/downloads`(913 MB) 与 `/var/www/html/downloads`(116 MB)、
服务器上的 `apps/minipay-frontend/android` 源码副本都已删除。实测
`https://download.su46proj.site/downloads/minipay-latest.apk`、`https://dl.su46proj.site/downloads/minipay-latest.apk`、
`http://122.152.221.201/minipay-latest.apk` 均不再返回安装包；7 个线上主机页面 `apk_refs=0`。
唯一遗留：token 缺 `Rulesets` 权限，读不到针对 `/downloads/*` 的 Cloudflare 缓存规则（已无主机服务该路径）。

> 关于凭据：`cloudflare-mcp/check-token.ps1` 之前判 `CLOUDFLARE_API_TOKEN` 无效是**误判**——真实原因是从国内
> 直连 `api.cloudflare.com` 超时。有效 token 在 `C:\minipay-keys\cf-token.txt`（53 字符，`cfut_` 前缀，
> `/user/tokens/verify` 返回 `status: active`）；把它传到服务器（能连通 CF API）再执行 curl 即可。
> zone `b8cf8f34485c56fb71a36e4e1ebb75c5`、account `fb03d829c45b4ade0be129650ebc44bf`。
> 服务器上没有 node/npx，Worker 的上传改用 Cloudflare API 的 multipart 接口（见 `integrations/cloudflare/pay-edge-legacy/R2-DEPLOYMENT.md`）。

验收（本次改动后跑同一套线上脚本）：`scripts/k3s/acceptance-server.ps1`
`-Mobile 13900002801 -SkipAi -PayPassword 123456 -ConfirmTransfer -MerchantQr <真实商户码> -OriginIp 122.152.221.201`
→ **21 passed / 0 failed**（含 H5 外壳、CSRF、短信登录、钱包/账单、转账落账、扫码付款商户全链路）。

### 2026-09-27 资源清理与内存优化（实况记录）

**磁盘：根分区已用 31 G → 20 G，可用 6.7 G → 19 G（83% → 52%）**，共释放约 11 GB：

| 项 | 释放 | 说明 |
|---|---|---|
| `minipay-infra-seata-server-1` 的容器 stdout 日志 | **8.4 GB** | 15 天涨到 8,941,736,558 字节：Seata 每笔全局事务都打 INFO。已 `truncate`，并加了两层兜底（见下） |
| `/var/log/journal` | 0.69 GB | `journalctl --vacuum-size=100M` |
| `/home/ubuntu/apps/{incoming,minipay-backend}` | 1.0 GB | 8 月 9 日的旧部署 tar 包与过期检出（线上 compose 用的是 `/home/ubuntu/minipay/...`，与它们无关；已确认无 systemd/cron 引用） |
| `/var/www` | 0.26 GB | 宿主 nginx 时代的静态站，`kubectl` 里没有任何 pod 挂载它 |
| `/usr/local/qcloud` | 0.81 GB | 腾讯云 agent（YunJing/barad/tat_agent/stargate）：进程已停、cron 钩子已删（`/etc/cron.d/yunjing`、`sgagenttask`、`tat_agent.service` 已 mask） |
| `/tmp` | 0.32 GB | 旧镜像 tar、会话 cookie 等陈旧文件 |

> 兜底：`/etc/docker/daemon.json` 已加 `log-opts {max-size: 20m, max-file: 3}`（dockerd 只在启动时读它，
> 对**之后**创建的容器生效）；另加 `/etc/cron.d/minipay-docker-log-guard` 每天 04:20 截断超过 200 MB 的容器日志。
> ⚠️ 顺手踩到一个坑：`docker compose` 重建容器如果不带 `--env-file .env.local` 会因缺变量失败；
> 而带上它又会用 `INFRA_BIND_HOST=127.0.0.1` 把 seata 只绑到本机，导致集群（`SEATA_SERVER_ADDR=10.0.0.16:8091`）
> 连不上、转账报 `503 DISTRIBUTED_TRANSACTION_UNAVAILABLE`。重建必须
> `sudo env INFRA_BIND_HOST=0.0.0.0 docker compose --env-file .env.local -f compose.yaml -f compose.server.yaml up -d --no-deps --force-recreate <svc>`。

**内存：可用内存 326 MiB → 约 660 MiB，7 个 Java 服务 RSS 合计约 1.72 GB → 1.36 GB**：

- 腾讯云 agent 进程（YDService 68 + barad 14 + tat/sgagent ≈ 85 MiB）已彻底停掉；`multipathd`、
  `ModemManager`、`upower`、`fwdisk` 一类无用服务也停了（≈ 30 MiB）。
- **堆比例是真的降下来了**：`server-lite` 原来只往 ConfigMap 注入 `JAVA_TOOL_OPTIONS`，
  而镜像 ENTRYPOINT 是 exec 形式固定 `-XX:MaxRAMPercentage=75.0` —— JVM 把 `JAVA_TOOL_OPTIONS`
  前置展开，同一 `-XX` 参数以最后一个为准，所以 `-XX:+PrintFlagsFinal` 里永远显示 `{command line} 75.0`。
  现在 base 里给 6 个 Java 服务显式换成 shell 入口
  `command: ["sh","-c","exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75.0} -jar /app/app.jar"]`，
  堆比例由 `minipay-runtime` 的 `JAVA_OPTS=-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15` 决定。
  各服务 RSS：payment 319→245、identity 318→275、wallet 291→214、consumer-bff 179→152、
  admin-bff 170→161、management-bff 186→154 MiB。
- `vm.swappiness=10` 写入 `/etc/sysctl.d/99-minipay-memory.conf`（swap 当时已用约 0.9 GiB，
  因可用内存不足以安全 `swapoff`，暂未强制回收，交给内核按需换入）。

### 2026-09-27 消费者 H5 补功能：相机扫码

`apps/consumer-h5` 的 `/pay` 新增「扫一扫商户收款码」：复用浏览器自带 `BarcodeDetector`（零新依赖），
只在用户点开时申请摄像头、识别到一帧即释放；不支持的环境退化为「粘贴收款码」提示。
新增 `src/utils/scan.ts`（深链/裸令牌/带 token 链接的统一解析）与 `src/utils/scan.test.ts`（6 例）。
镜像 `suqihang/consumer-web:0.1.0-k3s.3` 已构建、导入节点并滚动上线（本地 `pnpm build` +
`docker build --provenance=false --sbom=false` + `docker save` + `k3s ctr images import`），
线上 `p__pay.fc6df3ee.async.js` 含 `BarcodeDetector` 与 `getUserMedia`，验收 17 passed / 0 failed。

### 重建 consumer-bff / identity-service（每次改这两个服务都适用）

`overlays/server/private/digests` 里 pin 的 digest 是更早的构建，本次上线改用 tag + `IfNotPresent`
（见 overlay 的 patch，原因同 consumer-web：新镜像只在节点本地 containerd）。

```bash
# 1) 先跑测试（流水线打镜像是 -DskipTests，不能拿它当验证）
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
mvn -B -ntp -pl services/consumer-bff,services/identity-service -am verify

# 2) 只重建这两颗（等价于 ci-pipeline.ps1 里 Java 服务那一段；上下文是仓库根）
mvn -B -ntp -pl services/consumer-bff -am -DskipTests package
docker build --provenance=false --sbom=false --file docker/k3s-service.Dockerfile \
  --build-arg MODULE=services/consumer-bff -t suqihang/consumer-bff:<新 tag> .
mvn -B -ntp -pl services/identity-service -am -DskipTests package
docker build --provenance=false --sbom=false --file docker/k3s-service.Dockerfile \
  --build-arg MODULE=services/identity-service -t suqihang/identity-service:<新 tag> .

# 3a) 能推 Docker Hub 时：推送 → 记录 digest → 重新 pin → apply
pwsh -NoProfile -File scripts/k3s/push-and-record-digests.ps1 -Only consumer-bff,identity-service
pwsh -NoProfile -File scripts/k3s/pin-image-digests.ps1
kubectl kustomize deploy/k3s/overlays/server | kubectl apply -f -

# 3b) 推不上去时（本机 DNS 拿不到 registry-1.docker.io）：节点本地导入 + tag 覆盖
docker save suqihang/consumer-bff:<tag> suqihang/identity-service:<tag> | \
  ssh <server> 'sudo k3s ctr -n k8s.io images import -'
# 并把 overlay 里这两条 patch 的 tag 改成新值，再 apply；此时**不要**跑 pin-image-digests.ps1
```

> 服务器上也能直接构建（它有 JDK 21 + Maven + Docker）：把源码 tar 上去，
> 用仓库自带 `mvnw`（系统 mvn 是 3.8.7，低于 enforcer 要求的 3.9+；且 `mvnw` 是 CRLF，
> Linux 上要先 `tr -d '\r' < mvnw > mvnw.lf`）。

### C 端上线验收脚本（对着线上真环境跑）

`scripts/k3s/acceptance-server.ps1`：**只用公网 URL**（不需要 ssh / kubeconfig），把切换后的关键链路
逐条断言并给出可执行结论，退出码 0 表示全过。

```powershell
# 只读验收（默认：转账只 prepare，随后自动 DELETE 掉意图，不动钱）
pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -OriginIp <节点公网IP>

# 连扣款一起验收（走 1 分的沙箱转账）
pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -ConfirmTransfer -PayPassword 123456 -OriginIp <节点公网IP>

# 跳过真实模型调用（省一次 MiMo 请求）
pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -SkipAi
```

覆盖：H5 外壳与 bundle、CSRF、短信登录（含 429 退避）、钱包/账单/收款码、**米灵 SSE 逐字 + 落库**、
转账 prepare→confirm 与余额账实相符、付款扫码路由与参数门禁、落地页是否还在发 APK。
`-OriginIp` 会额外用 `curl --resolve` 直连集群源站，用来区分「源站还没改对」和「只是 CDN 缓存陈旧」
（2026-09-27 实测结论就是后者：源站 `apk_refs=0`，公网仍 6 处，需要在腾讯云控制台刷新
`https://pay.su46proj.site/`）。

### 上线顺序

```bash
# ① 构建并推送镜像。规范入口是流水线脚本（依次：构建 8 个 Java/BFF 服务镜像
#    —— 含本次改动的 consumer-bff 与 identity-service —— 再构建 4 个前端静态镜像
#    （merchant/ops/admin/**consumer-web**），然后推送 + 记录 digest + 生成 overlay）：
pwsh -NoProfile -File scripts/k3s/ci-pipeline.ps1 -Version <新 tag>
#
#    注意两点：
#    a) 流水线用 `mvn ... -DskipTests` 打镜像（平台既有约定），测试仍应单独跑绿；
#    b) 米灵在**另一个仓**（ai-agent-scaffold-lite），流水线管不到它，需要单独构建后把
#       digest 记进来：
cd <ai-agent-scaffold-lite 目录>
mvn -B -ntp clean package
docker build -t suqihang/miling-service:<新 tag> .
pwsh -NoProfile -File <后端仓>/scripts/k3s/push-and-record-digests.ps1 -Only miling
pwsh -NoProfile -File <后端仓>/scripts/k3s/pin-image-digests.ps1
#
#    consumer-web 的镜像定义是前端仓共享的静态服务 Dockerfile，上下文必须是【仓库根目录】，
#    因为它 COPY apps/<APP>/dist/；镜像名是 consumer-web，而 APP 要传目录名 consumer-h5。
#    流水线里已经按这个差异配好了（`APP` 与 `name` 分开），手工构建时别传错：
cd <前端仓 mini-pay-ai-frontend>
pnpm --filter @minipay/consumer-h5 build
docker build -f docker/k3s-web.Dockerfile --build-arg APP=consumer-h5 \
  -t suqihang/consumer-web:<新 tag> .
#
#    若 Docker Hub 推不上去（校园网对 registry-1.docker.io 解析失败，见变更 #44/#45），
#    退回节点本地导入。此时 overlay 里给 consumer-web / miling-service 保留的
#    tag + IfNotPresent 两条 patch 必须留着：
docker save suqihang/miling-service:<新 tag> suqihang/consumer-web:<新 tag> -o minipay-h5.tar
scp minipay-h5.tar <server>:/tmp/ && ssh <server> 'k3s ctr -n k8s.io images import /tmp/minipay-h5.tar'

# ② 服务器上准备米灵密钥（不进 Git）：deploy/k3s/overlays/server/private/miling.env
#    至少包含 REDIS_PASSWORD（平台约定键名，与其它服务一致）与 MILING_MODEL_API_KEY。
#    2026-09-27 起线上用的是小米 MiMo：MILING_MODEL_API_KEY 取本机环境变量 MIMO_API_KEY
#    （模型列表见 https://api.xiaomimimo.com/v1/models，base-url 用 https://api.xiaomimimo.com/v1）；
#    MILING_MODEL_ENABLED=true 与 base-url/name 已写在 miling-service.yaml 里；
#    漏掉 MODEL_ENABLED 会让米灵完全不回话（它的默认值是 false），check-config.ps1 有门禁拦这个。
#    服务器上没有这个 env 时，可以先从既有 agent-runtime Secret 里搬（它会带 GLM 的 key）：
#      kubectl -n minipay get secret <agent-runtime-*> -o jsonpath='{.data.MODEL_API_KEY}' | base64 -d
#
# ⚠️ OAuth 回调地址必须两侧一致：identity 注册的 consumer-bff-client.redirect-uri 与
#    consumer-bff 发送的 minipay.consumer-bff.oauth-redirect-uri 是【精确匹配】的同一字符串
#    （identity 的 validateClient 做等值比较）。两侧默认值相同（http://localhost:8087/session/callback，
#    它只是逻辑标识、不会被真正访问），所以不改就没事；要改就**成对覆盖**
#    CONSUMER_BFF_OAUTH_REDIRECT_URI，只改一侧会直接 OAUTH_CLIENT_INVALID 登不进去。

# ③ 应用清单。注意：这份清单是**一次切换到位**的——新增 miling-service / consumer-web 的
#    同时，patch 会把 agent-service 与 commerce-service 缩到 0 副本。
#    想更保守就先注释掉 overlays/server/kustomization.yaml 里 agent-service 那条 patch，
#    apply 并验证 H5 的米灵可用后再放开、重跑一次。
kubectl kustomize deploy/k3s/overlays/server | kubectl apply -f -
bash scripts/k3s/apply-ngf-tuning.sh          # NGF 控制面资源与滚动策略，幂等，apply 后跑一次
kubectl -n minipay rollout status deploy/miling-service deploy/consumer-web deploy/consumer-bff

# ④ 平台验收：Deployment 期望状态、有意缩容项确无 Ready 端点、健康探针、Gateway 与 HTTPRoute
pwsh -NoProfile -File scripts/k3s/acceptance.ps1

# ⑤ 业务验收（这一步脚本替代不了，必须手点）：
#    https://app.su46proj.site/ 登录 → 米灵对话（SSE 逐字出）→ 钱包/账单 → 收款码
#    → 沙箱转账 → 扫码付款（用商户端收款码：识别 → 金额 → 支付密码 → 支付单成功）

# ⑥ 宿主机回收外卖中间件（数据卷保留；这两项已由 compose.server.yaml 的 profiles 覆盖声明）
cd deploy/compose-infra
docker compose -f compose.yaml -f compose.server.yaml --env-file .env.local stop yshop-mysql yshop-redis
docker compose -f compose.yaml -f compose.server.yaml --env-file .env.local up -d --remove-orphans
docker stats --no-stream    # 核对实际占用
```

### 回滚

- H5 入口异常：`kubectl -n minipay scale deploy consumer-web --replicas=0` 即可摘掉新入口，
  原有 ops/merchant/admin 完全不受影响。
- AI 对话异常：`kubectl -n minipay scale deploy agent-service --replicas=1` 暂时回到旧实现把
  `MILING_INTERNAL_URL` 改回 `http://agent-service:8080`。
- 外卖恢复：见本文件前面的外卖栈启停说明，并删掉 `compose.server.yaml` 里两条 `profiles` 覆盖。
- 数据卷（`yshop-mysql-data` / `yshop-redis-data`）**全程没有删除**，外卖数据完好。

### 本地验证覆盖不到什么（必须人工验收）

已由自动化验证覆盖：清单可构建（`kubectl kustomize`，含 patch 命中）、脚本语法、镜像可产出、
H5 的类型检查/单元测试/生产构建、BFF 与米灵的单元与集成测试（上游用 MockWebServer 桩掉）。

**没有覆盖**（本机没有可用 Docker daemon 与集群，无法执行）：

1. H5 → consumer-bff → identity/wallet/payment/**miling** 的**真实端到端链路**（登录、SSE 流式、`prepare → confirm`）；
2. Cloudflare 与 NGF 对 **SSE 长连接**的实际行为（15s 心跳是否足以避开边缘空闲断连、边缘是否缓冲）；
3. `app.su46proj.site` 的 DNS、证书与 HTTPRoute 是否真的按预期生效；
4. 内存收敛后的**实际占用**——本节表格是 request/limit 的推算值，不是实测值。

另外两处已知的"宽松"取舍（都不影响资金正确性，但要知道）：H5 对后端字段名采用"多候选 + 兼容 `...Cent`"
解析，只用于**列表/展示**字段，接口改名会退化成显示 0 而不是报错；转账授权金额走精确键名并有本地守卫。

### 第 ⑤ 步的具体清单（任一条失败先停下看回滚）

| # | 动作 | 期望 |
| --- | --- | --- |
| 1 | 打开 `https://app.su46proj.site/` | 出登录页；375px 与 768px 无横向滚动 |
| 2 | 任意演示手机号 + 验证码 `123456` | 登录成功，刷新后仍保持登录 |
| 3 | 米灵发一句「你好」 | **逐字**出现回复，并以 `stream.completed` 收尾（不是一直转到超时） |
| 4 | 钱包 / 账单 | 与运营端数据一致；账单金额不是 `¥0.00` |
| 5 | 收款码 | 出二维码，内容来自后端下发的 deepLink |
| 6 | 转账 `prepare → 支付密码 → confirm` | 结果页以 `GET /transfers/{transferNo}` 为准；重复提交不重复扣款 |
| 7 | `kubectl -n minipay logs deploy/consumer-bff --tail=100` | 无 500；日志中无支付密码、Cookie、完整 token |
| 8 | `kubectl -n minipay get pods` | miling / consumer-web / consumer-bff Ready；agent / commerce / yshop-* 为 0 |

第 3 与第 6 条是最容易"平台验收绿、业务其实不通"的两处：前者依赖**平台事件词汇**
（`message.delta` / `message.completed` / `stream.completed`）与 SSE 不被缓冲；
后者依赖 `amountFen` 随 confirm 一起送达身份服务，签发**限定金额**的一次性授权令牌。


