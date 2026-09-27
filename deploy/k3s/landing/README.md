# 落地页产物（自动生成，勿手改）

`pay.html` 与 `personal.html` 是 **Cloudflare 落地页源码的渲染结果**：

- 源：`integrations/cloudflare/landing-worker/src/pages.js`
  （`projectLandingPage()` → `pay.html`，`personalHomePage()` → `personal.html`）
- 生成与应用：`scripts/k3s/apply-landing-pages.ps1`
  （渲染 → 写回本目录 → 更新集群里的 `landing-pay` / `landing-personal` ConfigMap → 重启两个 Deployment）
- 只渲染不应用：`pwsh -NoProfile -File scripts/k3s/apply-landing-pages.ps1 -SkipApply`

## 为什么这两个文件在仓里

线上 `pay.su46proj.site` / `su46proj.site` 不是由 Cloudflare Worker 直接提供，而是由集群内
`landing-pay` / `landing-personal` 两个 Deployment 用 nginx 挂 ConfigMap 提供（各 2 副本）。
把渲染结果入库有两个好处：

1. 部署进 ConfigMap 的 HTML 在 Git 里可评审（`scripts/k3s/apply-landing-pages.ps1` 是幂等的：
   产物 MD5 与线上 ConfigMap 一致时 apply 是空操作）；
2. 以后改落地页不需要再靠仓外工具：改 `pages.js` → 跑脚本 → 刷边缘缓存。

## 注意：改完必须刷边缘缓存

两个域名都有边缘缓存，**改源站不会立刻改变公众看到的内容**：

| 域名 | 边缘 | 刷新方式 |
| --- | --- | --- |
| `pay.su46proj.site` | 腾讯云 CDN | 控制台「刷新预热 → URL 刷新」填 `https://pay.su46proj.site/` |
| `su46proj.site` | Cloudflare | 控制台「Caching → Configuration → Purge Everything / 按 URL 清理」 |

验证（区分源站与边缘）：

```powershell
pwsh -NoProfile -File scripts/k3s/acceptance-server.ps1 -OriginIp <节点公网IP>
# 该脚本第 8 项会同时报"源站 apk_refs"与"公网 apk_refs"，只有公网那份 >0 时说明是缓存问题
```
