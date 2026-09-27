# APK on Cloudflare R2 —— 已下线

**状态：APK 分发已随 Android 端一并从线上移除。** 本文件保留下来只是为了让后来者
明白"这里曾经有什么、为什么没了"，**不要**照它重新接一遍。

> 归档说明（2026-09-27）：本目录是**已从 Cloudflare 删除**的两个边缘 Worker 源码
> （`minipay-pay-edge` = `origin-8443-worker.js`、`origin-8443` = `origin-proxy-worker.js`），
> 连同 `callback.su46proj.site` 路由一起下线，源码保留在此以便追溯或恢复。
> 消费者 C 端现役的落地页 Worker 是同级的 `../landing-worker/`（Worker 名 `minipay-web`）。

下线原因、服务器侧还有什么被停掉、以及万一要恢复该怎么做，见前端仓库
`mini-pay-ai-frontend` 的 `android/RETIRED.md`（同一交付目录下的兄弟仓库）。

## 已经删除的东西

| 位置 | 原来是什么 | 现在的状态 |
|---|---|---|
| `origin-8443-worker.js`（本目录） | `APK_*` 常量、`serveApkFromR2()` 分片下发、`parseSingleRange()`、APK 专用缓存键与响应头 | 已删除；`APK_HOST` 改名为 `PAY_HOST`（它本来就只是支付站点的对外主机名） |
| `wrangler.toml`（本目录） | `[[r2_buckets]] MINIPAY_DOWNLOADS` 绑定、`run_worker_first` 里的 `/downloads/*` | 已删除 |
| `integrations/cloudflare/landing-worker`（后端仓，Worker 名 `minipay-web`） | 第 2 条路由 `download.su46proj.site` → `serveDownload()`（`DOWNLOADS` 绑定流式下发）、落地页里的「下载 Android App / 下载 APK」入口与 `APK_URL` 常量 | 已删除：`DOWNLOAD_HOSTS`/`serveDownload()` 与 `[[r2_buckets]]` 一并移除，落地页入口改成 `https://app.su46proj.site/`（消费者 H5） |
| Cloudflare R2 | 私有桶 `minipay-downloads` 与其对象（4 个 APK，约 161 MB） | **已删除**（2026-09-27）：4 个对象逐个 DELETE，桶已清空并删除；桶列表现为空 |
| Cloudflare R2 自定义域名 | `download.su46proj.site` → `minipay-downloads` | **已删除**（2026-09-27）：自定义域删除后 DNS 记录随之消失，`download.su46proj.site` 解析无记录 |
| Cloudflare DNS | `download.su46proj.site` CNAME、`dl.su46proj.site` CNAME、`_dnsauth.dl` TXT | **已删除**（2026-09-27）；`*.su46proj.site` 通配 A 记录保留 |
| 腾讯云 CDN | 域名 `dl.su46proj.site` 及其免费 DV 证书 | **已删除**（2026-09-27，`tccli cdn DeleteDomain`，域名数 12 → 11） |
| Cloudflare 缓存规则 | 针对 `/downloads/*` 的缓存规则 | 现有 token 缺 `Rulesets` 权限，读不到缓存规则列表（`pagerules` 为空说明没有旧版页面规则）。**已无实际影响**：没有任何主机再服务 `/downloads/*`。若要在控制台彻底清掉，给 token 加 `Zone → Cache Rules → Edit` 后删除该规则即可 |
| 两个 Worker 上的 R2 绑定 | `minipay-web` 的 `DOWNLOADS`、`minipay-pay-edge` 的 `MINIPAY_DOWNLOADS` | **已清除**（2026-09-27）：两个 Worker 重新上传后绑定列表为空，路由保持不变 |
| 源站服务器上的分发路径 | 宿主 nginx 的下载 vhost 与 `/var/www/**/downloads`（约 1.03 GB APK） | **已删除**（2026-09-27）：见下文「服务器侧」 |

## 2026-09-27 收尾执行记录

Cloudflare 侧（token 来自 `C:\minipay-keys\cf-token.txt`，账号 `fb03d829c45b4ade0be129650ebc44bf`，
zone `b8cf8f34485c56fb71a36e4e1ebb75c5`）。注意：**国内直连 Cloudflare API 会超时，把 token 传到服务器上执行即可**。

1. R2：列出 `downloads/` 前缀 → 4 个对象全部 DELETE → 桶空 → 删除 R2 自定义域名 → 删除桶。
2. DNS：`download.su46proj.site`（随自定义域删除）、`dl.su46proj.site`、`_dnsauth.dl.su46proj.site` 全部 DELETE。
3. 校验：`https://download.su46proj.site/downloads/minipay-latest.apk` 与
   `https://dl.su46proj.site/downloads/minipay-latest.apk` 均不再返回安装包；
   7 个线上主机页面 `apk_refs=0`；zone 级 + host 级 + URL 级缓存各 purge 一次
   （第一次 curl 仍命中边缘缓存的旧 200，purge 后为 404）。
4. Worker：服务器上没有 node/npx，改用 **Cloudflare API 的 multipart 上传**
   （`PUT /accounts/{id}/workers/scripts/{name}`，`metadata` 里 `bindings: []`）。
   `minipay-web` 上传 3 个模块 `index.js` / `pages.js` / `beian.js`；
   `minipay-pay-edge` 用 `?keep_assets=true` 保留已上传静态资源、换成本目录的无下载分支源码。
   两个 Worker 的路由均未改动，落地页 MD5 与仓库生成结果一致
   （`0fc77903552e4511fa27c597513a9638` / `10e85fe06aeb9f4e66fdec31ccada3f4`）。
   旧 bundle 备份在服务器 `/home/ubuntu/backups/minipay-web-deployed-bundle.js`、
   `minipay-pay-edge-deployed-bundle.js`。

### 服务器侧

宿主 nginx（`systemctl is-active nginx` = inactive/disabled，端口由 k3s 的
NGINX Gateway Fabric + klipper 占）上没有活体分发，但残留配置与文件仍在，已清理：

- `minipay-direct-download.conf` 的 sites-enabled 软链与 sites-available 文件（曾直发
  `http://122.152.221.201/minipay-latest.apk`）；
- `snippets/minipay-mobile-api.conf` 的 `location = /apk`、`location ^~ /downloads/`；
- `snippets/minipay-pay-8443-routes.inc` 的 `location ^~ /downloads/`、
  `location ^~ /__callback/downloads/`（改前 `nginx -t` 校验、改后 reload 均通过，备份在
  `/home/ubuntu/backups/apk-retire-*`）；
- `/var/www/pay.su46proj.site/downloads`（913 MB）、`/var/www/html/downloads`（116 MB）、
  备份目录里的旧 APK（38 MB）、服务器上的 `apps/minipay-frontend/android` 源码副本与
  `run-android.sh`、`deploy-login-map-android-20260809.sh`；根分区可用空间 5.4 G → 6.4 G。

### 顺带发现（与 Android 无关，未擅自改路由）

`callback.su46proj.site/*` 曾指向 `minipay-pay-edge`，但该 Worker 的作用是回源到
`pay.su46proj.site:8443/__callback/...`，而这套 8443 宿主 nginx 链路随 k3s 迁移已经不存在
（8443 端口关闭、仓库里对 `callback.su46proj.site` 零引用、无对应 HTTPRoute）。
**2026-09-27 已清理**：删除该 Worker 路由、删除 `minipay-pay-edge` 与 `origin-8443` 两个脚本、
删除 `callback.su46proj.site` 的 DNS 记录（zone 里 `*.su46proj.site` 通配仍在）。
现在该主机返回 404（此前是连接失败），Cloudflare zone 里只剩 6 条路由，全部指向 `minipay-web`。
源码仍在本目录，需要恢复时按上面「部署提醒」的方式重新 deploy 即可。

## 历史记录（仅供追溯）

- Bucket：`minipay-downloads`（private，Standard）
- Object key：`downloads/minipay-latest.apk`
- 曾经的分发入口：`https://dl.su46proj.site/downloads/minipay-latest.apk`（腾讯云 CDN）与
  `https://download.su46proj.site/downloads/minipay-latest.apk`（R2 自定义域名直连，不经过 Worker）
- 记录过的尺寸：`38,928,338` 与 `40,200,024` / `40,252,600` 字节（多次换包，以最新一次发版为准）
- 支持过 `GET` / `HEAD` / 单段 Range / `If-Range` / `If-None-Match`

## 如果确实要恢复分发

先想清楚：这次下线的直接动因就是安装包维护成本与 40 MB 下载体验。真要恢复，建议
**换一条更省事的路**（例如把安装包放到对象存储直链 + 一次性的下载页），
而不是把上面这套分片缓存逻辑再写回 Worker。

## 部署提醒

两个 Worker 的改动都**必须重新部署才生效**。本目录的 `wrangler.toml` 里
`pay.su46proj.site/*` 与 `food.su46proj.site`（custom domain）**不能直接部署**：
线上这两条并不属于 `minipay-pay-edge`（`pay` 属于 `minipay-web`、`food` 当前没有 Worker 自定义域），
照搬会抢走路由。线上的 `minipay-pay-edge` 只有 `callback.su46proj.site/*` 一条路由。

```bash
# ① 落地页 worker（本仓 integrations/cloudflare/landing-worker，Worker 名 minipay-web；
#    路由与仓库配置完全一致，可放心 deploy）
cd integrations/cloudflare/landing-worker
CLOUDFLARE_API_TOKEN=... npx wrangler deploy

# ② 本目录的 origin worker（只改代码、不动路由时用 API 上传；服务器无 node 时也可这样）
#    ⚠️ 这两个 Worker 已于 2026-09-27 从 Cloudflare 删除，下面是"万一要恢复"的命令。
curl -X PUT "https://api.cloudflare.com/client/v4/accounts/<acct>/workers/scripts/minipay-pay-edge?keep_assets=true" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -F 'metadata={"main_module":"origin-8443-worker.js","compatibility_date":"2026-08-09","bindings":[]};type=application/json' \
  -F 'origin-8443-worker.js=@origin-8443-worker.js;type=application/javascript+module'
```

落地页 worker 是多模块（`index.js` → `pages.js` → `beian.js`），用 API 上传时要**三个文件都带上**，
否则报 `Uncaught Error: No such module "beian.js"`；`police-badge.png` 已内联成 data URI，不需要上传。

部署后确认两个旧入口都不再返回安装包：

```bash
curl -sI https://dl.su46proj.site/downloads/minipay-latest.apk        # 期望：404 / 域名已停用
curl -sI https://download.su46proj.site/downloads/minipay-latest.apk  # 期望：404（桶与自定义域名已删）
curl -s  http://122.152.221.201/minipay-latest.apk                    # 期望：404（宿主下载 vhost 已停用）
```

另外打开 `https://pay.su46proj.site/` 确认落地页上已没有「下载 Android App / 下载 APK」按钮，
取而代之的是「打开消费者 H5」（`https://app.su46proj.site/`）。
