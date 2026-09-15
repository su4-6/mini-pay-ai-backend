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

维护页镜像：`docker build -f deploy/k3s/maintenance/Dockerfile -t suqihang/maintenance-page:0.1.0 deploy/k3s/maintenance`

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
