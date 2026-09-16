# MiniPay Web Landing Worker

`pay.su46proj.site` 与 `su46proj.site` 的静态落地页，以及 Android APK 的下载分发，全部由同一个 Cloudflare Worker 提供。

页面 HTML 内联在 `src/pages.js` 中（无 CDN、无外部字体、无统计脚本），Worker 只做路由与响应头处理。

## 目录结构

```
landing-worker/
├── src/index.js           # Worker 入口与路由逻辑
├── src/pages.js           # personalHomePage() / projectLandingPage()，返回完整 HTML 文档
├── src/beian.js           # 公安备案图标的 data URI（由 police-badge.png 生成）
├── src/police-badge.png   # 公安备案图标原图（36×40，与 deploy/k3s/maintenance/beian.png 同源）
├── wrangler.toml          # name / main / compatibility_date / R2 绑定
└── README.md
```

## 它提供什么

| 内容 | 说明 |
| --- | --- |
| 个人主页 | 作者介绍、项目展示、技术栈、示例账号、App 下载、联系方式 |
| 产品落地页 | MiniPay AI 定位、核心能力、架构一览、演示账号、使用说明、项目跳转 |
| APK 下载 | `download.su46proj.site` 从 R2 流式下载 Android 安装包 |

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
| 2 | `host === download.su46proj.site` | 从 R2 绑定 `DOWNLOADS` 流式返回对象 |
| 3 | `host === su46proj.site` 或 `www.su46proj.site` | 返回 `personalHomePage()`，`text/html; charset=utf-8`，200 |
| 4 | `host === pay.su46proj.site` | 返回 `projectLandingPage()`，`text/html; charset=utf-8`，200 |
| 5 | 其他所有 host | `fetch(request)` 原样回源 |

第 5 条是关键：`ops.`、`merchant.`、`admin.`、`food.`、`food-admin.`、`identity.`、`payment.`、`wallet.`、`commerce.`、`agent.` 等子域由 Kubernetes 集群直接提供服务，Worker 不做任何拦截或改写。

## APK 下载（R2）

`download.su46proj.site` 必须**路由到本 Worker**（自定义域名或 DNS 记录指向该 Worker），否则下载请求不会被处理。

绑定：

```toml
[[r2_buckets]]
binding = "DOWNLOADS"
bucket_name = "minipay-downloads"
```

- **R2_BUCKET**：`minipay-downloads`，其中已存在对象 `downloads/minipay-latest.apk`（约 39 MB）。
- 对象键 = 请求路径去掉开头 `/`，即 `https://download.su46proj.site/downloads/minipay-latest.apk` → 键 `downloads/minipay-latest.apk`。
- 响应头：`Content-Type` 取对象的 `httpMetadata.contentType`，缺失时按扩展名推断（`.apk` → `application/vnd.android.package-archive`，`.txt` → `text/plain`，`.json` → `application/json`，其他 → `application/octet-stream`）；同时返回 `Content-Length`、`Content-Disposition: attachment; filename="<文件名>"`、`ETag`、`Cache-Control: public, max-age=3600`。
- 响应体直接使用 `object.body`（ReadableStream），不会把整个文件读入内存；因此大文件下载不会超出 Worker 内存上限。
- `env.DOWNLOADS` 未配置时返回 **503** `text/plain`；对象不存在时返回 **404** `text/plain`。

## 本地开发

```bash
cd integrations/cloudflare/landing-worker
npx wrangler dev
```

本地验证多域名路由可用 `--host` 指定 Host：

```bash
npx wrangler dev --host su46proj.site
npx wrangler dev --host pay.su46proj.site
npx wrangler dev --host download.su46proj.site
```

## 部署

```bash
cd integrations/cloudflare/landing-worker
npx wrangler deploy
```

部署前确认：

1. 已在 Cloudflare 中创建 R2 桶 `minipay-downloads` 并上传 `downloads/minipay-latest.apk`。
2. `su46proj.site`、`www.su46proj.site`、`pay.su46proj.site`、`download.su46proj.site` 四个域名已绑定到该 Worker；其余子域保持解析到 K3s 源站，不要绑定到本 Worker。
3. 源站证书使用 Cloudflare Origin CA，回源流量仍需经过集群入口 NGINX Gateway Fabric。

## 页面约束

- 单文件自包含：无 CDN、无 Web Font、无第三方 JS，打开 HTML 即可渲染。
- 移动端优先响应式，使用 CSS 自定义属性、卡片、渐变与 hover 过渡。
- 外链均为 `target="_blank" rel="noopener"`，且只指向 `ops / merchant / admin / food / food-admin / download` 这几个 `su46proj.site` 子域。
