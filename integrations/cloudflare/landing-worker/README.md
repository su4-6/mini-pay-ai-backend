# MiniPay Web Landing Worker

`pay.su46proj.site` 与 `su46proj.site` 的静态落地页由一个 Cloudflare Worker 提供。

页面 HTML 内联在 `src/pages.js` 中（无 CDN、无外部字体、无统计脚本），Worker 只做路由与响应头处理。

> **APK 分发已下线**：消费者端从 Android App 换成 H5 后，`download.su46proj.site` 的 R2 自定义域名、
> `DOWNLOADS` 绑定与 Worker 里的下载分支都已删除，落地页的入口改为 `https://app.su46proj.site/`。
> 退役原因与恢复步骤见前端仓库 `android/RETIRED.md`。

## 目录结构

```
landing-worker/
├── src/index.js           # Worker 入口与路由逻辑
├── src/pages.js           # personalHomePage() / projectLandingPage()，返回完整 HTML 文档
├── src/beian.js           # 公安备案图标的 data URI（由 police-badge.png 生成）
├── src/police-badge.png   # 公安备案图标原图（36×40，与 deploy/k3s/maintenance/beian.png 同源）
├── wrangler.toml          # name / main / compatibility_date / routes
└── README.md
```

## 它提供什么

| 内容 | 说明 |
| --- | --- |
| 个人主页 | 作者介绍、项目展示、技术栈、消费者 H5 入口、联系方式 |
| 产品落地页 | MiniPay AI 定位、核心能力、架构一览、四个在线入口、演示账号与 H5 使用说明 |

两个页面均包含完整备案页脚：ICP 备案（`豫ICP备2026043015号` → https://beian.miit.gov.cn/ ）与
公安联网备案（图标 + `豫公网安备41010502008025号` → https://beian.mps.gov.cn/#/query/webSearch?code=41010502008025 ），
以及演示环境声明。图标按「图标在左、编号在右」排版，整块可点击。

> 图标用 data URI 内联，页面仍然**零外部请求**（`wrangler.toml` 里也写明不依赖外部资源）。
> 换图标：替换 `src/police-badge.png` 后重新生成 `src/beian.js` 的 data URI，再 `wrangler deploy`。

## 路由表

按顺序匹配，先命中先返回。

| 顺序 | 条件 | 行为 |
| --- | --- | --- |
| 1 | 路径以 `/api/`、`/oauth2/`、`/login`、`/logout`、`/openapi/`、`/admin-api/`、`/app-api/`、`/actuator/`、`/.well-known/` 开头 | `fetch(request)` 原样回源（任何 host 都生效，绝不返回 HTML） |
| 2 | `host === su46proj.site` 或 `www.su46proj.site` | 返回 `personalHomePage()`，`text/html; charset=utf-8`，200 |
| 3 | `host === pay.su46proj.site` | 返回 `projectLandingPage()`，`text/html; charset=utf-8`，200 |
| 4 | 其他所有 host | `fetch(request)` 原样回源 |

第 4 条是关键：`ops.`、`merchant.`、`admin.`、`app.`、`identity.`、`payment.`、`wallet.`、`agent.` 等子域由 Kubernetes 集群直接提供服务，Worker 不做任何拦截或改写。

## Android 分发已退役（原 APK / R2 分支）

历史上 `download.su46proj.site`（R2 自定义域名，桶 `minipay-downloads`，对象 `downloads/minipay-latest.apk` 约 39 MB）
直连 bucket 分发安装包，Worker 里另有一条 `DOWNLOADS` 绑定的兜底分支。两端现已全部删除：

- Worker：`DOWNLOAD_HOSTS`、`serveDownload()` 与其辅助函数、`plainResponse()` 已移除；`download.su46proj.site`
  现在落到第 4 条原样回源（该主机不再解析到任何服务）。
- `wrangler.toml`：`[[r2_buckets]]` 绑定已移除。
- 线上收尾完成（2026-09-27）：R2 桶 `minipay-downloads` 与其中 4 个 APK 对象、R2 自定义域名
  `download.su46proj.site`、DNS 记录 `download`/`dl`、腾讯云 CDN 域名 `dl.su46proj.site`
  均已删除；本 Worker 用仓库源码重新上传，绑定列表为空、路由不变
  （`https://pay.su46proj.site/downloads/minipay-latest.apk` 已 404，落地页 `apk_refs=0`）。
  唯一遗留：Cloudflare 针对 `/downloads/*` 的缓存规则读不到（token 缺 `Rulesets` 权限），
  但已无主机服务该路径，无实际影响；服务器侧的宿主 nginx 下载 vhost 与 `/var/www/**/downloads`
  （约 1.03 GB）也已删除，细节见同级归档目录 `../pay-edge-legacy/R2-DEPLOYMENT.md`。

恢复做法：重新上传对象 → 重新添加 R2 自定义域名/绑定 → 在 `wrangler.toml` 恢复 `[[r2_buckets]]` 与路由，
并用 `android/RETIRED.md` 里的步骤重建 Android 端。

## 本地开发

```bash
cd integrations/cloudflare/landing-worker
npx wrangler dev
```

本地验证多域名路由可用 `--host` 指定 Host：

```bash
npx wrangler dev --host su46proj.site
npx wrangler dev --host pay.su46proj.site
```

## 部署

```bash
cd integrations/cloudflare/landing-worker
npx wrangler deploy
```

部署前确认：

1. R2 桶 `minipay-downloads`、`download.su46proj.site` 的 R2 自定义域名与腾讯云 CDN `dl.su46proj.site` 均已在控制台删除（2026-09-27，本仓已无任何引用）。
2. `su46proj.site`、`www.su46proj.site`、`pay.su46proj.site` 三个域名已绑定到该 Worker；
   `ops / merchant / admin` 三个子域也绑定了路由，但**只用来做静态外壳的边缘缓存**（见下节），其余子域保持解析到 K3s 源站，不要绑定到本 Worker。
3. 源站证书使用 Cloudflare Origin CA，回源流量仍需经过集群入口 NGINX Gateway Fabric。

## 控制台静态外壳的边缘缓存（变更 #61）

`ops / merchant / admin` 三端的 SPA 外壳（index.html）与用户无关，却因为源站 `Cache-Control: no-cache`
每次都要从 Cloudflare 边缘回源上海，国内实测冷启动 TTFB 2.9–3.8 s。Worker 里用 Cache API 把它缓存在边缘：

| 规则 | 值 |
| --- | --- |
| 命中主机 | `ops.su46proj.site` · `merchant.su46proj.site` · `admin.su46proj.site` |
| 缓存对象 | 仅 `GET/HEAD` + `200` + `text/html` + **无 `Set-Cookie`** |
| 不缓存路径 | `/api/` `/oauth2/` `/login` `/logout` `/session` `/identity/` `/actuator/` `/internal/` `/.well-known/` `/openapi/` `/merchant/oauth2` `/callback` |
| 边缘 TTL | 600 s（`s-maxage`），过期自动回源 |
| 浏览器 | `no-cache, must-revalidate`（**故意不让浏览器缓存**：外壳过期会指向被替换的 hash 资源而白屏） |
| 调试头 | 响应带 `x-minipay-edge: HIT / MISS / BYPASS` |

**发新版前端后必须刷新外壳缓存**（否则最多 10 分钟内地边缘仍供旧 HTML）：

```bash
curl -X POST "https://api.cloudflare.com/client/v4/zones/<zone>/purge_cache" \
  -H "Authorization: Bearer <CF token>" -H 'Content-Type: application/json' \
  --data '{"files":["https://ops.su46proj.site/ops/login","https://merchant.su46proj.site/merchant/login","https://admin.su46proj.site/"]}'
```

已实测：purge 后下一次请求回到 `MISS`，再下一次 `HIT`。

## 页面约束

- 单文件自包含：无 CDN、无 Web Font、无第三方 JS，打开 HTML 即可渲染。
- 移动端优先响应式，使用 CSS 自定义属性、卡片、渐变与 hover 过渡。
- 外链均为 `target="_blank" rel="noopener"`，且业务入口只指向 `app / ops / merchant / admin` 四个仍在展示范围内的 `su46proj.site` 子域。
