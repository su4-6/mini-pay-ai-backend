/**
 * MiniPay AI -- Cloudflare Worker entry router.
 *
 * Responsibilities:
 *   1. Render two self-contained HTML pages (personal homepage + product landing).
 *   2. Serve the Android APK from the R2 bucket on download.su46proj.site.
 *   3. Pass every other host and every API path through to the Kubernetes origin.
 */

import { personalHomePage, projectLandingPage } from './pages.js';

/** Hosts that this Worker renders itself. Everything else is passed through. */
const PERSONAL_HOSTS = new Set(['su46proj.site', 'www.su46proj.site']);
const LANDING_HOSTS = new Set(['pay.su46proj.site']);
const DOWNLOAD_HOSTS = new Set(['download.su46proj.site']);

/** Path prefixes that must always reach the origin, never return HTML or files. */
const API_PATH_PREFIXES = [
  '/api/',
  '/oauth2/',
  '/login',
  '/logout',
  '/openapi/',
  '/admin-api/',
  '/app-api/',
  '/actuator/',
  '/.well-known/',
];

const CONTENT_TYPES = {
  apk: 'application/vnd.android.package-archive',
  txt: 'text/plain; charset=utf-8',
  json: 'application/json; charset=utf-8',
};

const DEFAULT_CONTENT_TYPE = 'application/octet-stream';

/**
 * 控制台静态外壳的 Worker 边缘缓存（**变更 #61**）。
 *
 * 背景：ops/merchant/admin 的 HTML 外壳由 K3s 的 nginx 提供，响应头是
 * `Cache-Control: no-cache` 且 `cf-cache-status: DYNAMIC` —— 每次打开页面，Cloudflare 边缘
 * 都要转回上海源站，国内实测 TTFB 冷启动 2.9~3.8 秒。
 *
 * SPA 外壳与用户无关（响应里没有 Set-Cookie，也不随登录态变化），所以在这里用 Cache API
 * 把它缓存在边缘：命中时直接返回（实测 TTFB ~0.2 秒），未命中才回源并顺手写入缓存。
 *
 * - 只缓存 GET/HEAD + 200 + text/html + 无 Set-Cookie 的响应
 * - 登录/授权/接口路径一律不缓存（走回源）
 * - 发新版前端后：等 10 分钟自动过期，或 purge 对应 URL 立即生效
 */
const CONSOLE_CACHE_HOSTS = new Set(['ops.su46proj.site', 'merchant.su46proj.site', 'admin.su46proj.site']);
const CONSOLE_CACHE_TTL_SECONDS = 600;
const CONSOLE_CACHE_BYPASS_PREFIXES = [
  '/api/', '/oauth2/', '/login', '/logout', '/session', '/identity/',
  '/actuator/', '/internal/', '/.well-known/', '/openapi/', '/merchant/oauth2',
  '/callback',
];

function isConsoleCacheableRequest(request, url) {
  if (!CONSOLE_CACHE_HOSTS.has(url.hostname)) return false;
  if (request.method !== 'GET' && request.method !== 'HEAD') return false;
  return !CONSOLE_CACHE_BYPASS_PREFIXES.some((prefix) => url.pathname.startsWith(prefix));
}

/**
 * 浏览器侧一律不缓存 HTML 外壳（`no-cache, must-revalidate`），只有边缘缓存生效：
 * 外壳文件只有 0.6 KB，浏览器重新取一次的成本可以忽略；反过来，如果让浏览器缓存 4 小时
 * （本区 browser_cache_ttl=14400 会覆盖 max-age），发新版后旧外壳会指向已被替换的 hash 资源，
 * 用户就会白屏。**边缘缓存 + 浏览器不缓存** 才是对发版最安全的组合。
 */
const CONSOLE_CACHE_BROWSER_TTL = 'no-cache, must-revalidate';

async function serveConsoleWithEdgeCache(request, ctx) {
  const cache = caches.default;
  const cacheKey = new Request(new URL(request.url).toString(), { method: 'GET' });

  const hit = await cache.match(cacheKey);
  if (hit) {
    const headers = new Headers(hit.headers);
    headers.set('x-minipay-edge', 'HIT');
    headers.set('cache-control', CONSOLE_CACHE_BROWSER_TTL);
    return new Response(hit.body, { status: hit.status, statusText: hit.statusText, headers });
  }

  const origin = await fetch(request);
  const headers = new Headers(origin.headers);
  const contentType = headers.get('content-type') || '';
  const storable = origin.status === 200
    && contentType.includes('text/html')
    && !origin.headers.has('set-cookie');
  headers.set('x-minipay-edge', storable ? 'MISS' : 'BYPASS');
  headers.set('cache-control', CONSOLE_CACHE_BROWSER_TTL);

  if (storable) {
    const cacheHeaders = new Headers(origin.headers);
    cacheHeaders.set('cache-control', `public, s-maxage=${CONSOLE_CACHE_TTL_SECONDS}`);
    cacheHeaders.set('x-minipay-edge', 'HIT');
    const toCache = new Response(origin.clone().body, {
      status: origin.status,
      statusText: origin.statusText,
      headers: cacheHeaders,
    });
    ctx.waitUntil(cache.put(cacheKey, toCache));
  }

  return new Response(origin.body, { status: origin.status, statusText: origin.statusText, headers });
}

/**
 * 关于 pay 主机的回源端口（历史记录，**变更 #39 复核后已不再需要绕过**）
 *
 * 早期这套链路里 `pay.su46proj.site` 走的是 8443 老网关（本地存档
 * `pay-site/origin-rule.json` 记的就是那条 Origin Rule：`route -> origin.port = 8443`）。
 * 8443 下线后，`pay.su46proj.site/api/**` 一度回源失败、Cloudflare 直接返回 521。
 * 一度在 Worker 里做过「pay 的 API 路径换主机回源」的绕过。
 *
 * 2026-09-13 复核：zone 里现在只剩一条 Origin Rules，动作是「重写目标端口 → 443」，
 * 关掉绕过分支后实测
 *   GET https://pay.su46proj.site/api/v1/csrf  -> 404（与 su46proj.site 一致，不再是 521）
 * 因此**绕过已移除**，API 路径统一 `return fetch(request)`。
 */

function isApiPath(pathname) {
  const path = pathname.toLowerCase();
  for (const prefix of API_PATH_PREFIXES) {
    if (prefix.endsWith('/')) {
      if (path.startsWith(prefix)) return true;
    } else if (path === prefix || path.startsWith(prefix + '/')) {
      return true;
    }
  }
  return false;
}

function htmlResponse(htmlBody) {
  return new Response(htmlBody, {
    status: 200,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'public, max-age=300, s-maxage=600',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'strict-origin-when-cross-origin',
      'x-frame-options': 'SAMEORIGIN',
      'content-language': 'zh-CN',
    },
  });
}

function plainResponse(body, status) {
  return new Response(body, {
    status,
    headers: {
      'content-type': 'text/plain; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    },
  });
}

/** Map the request path to an object key: strip exactly one leading slash. */
function objectKeyFromPath(pathname) {
  let key = pathname;
  while (key.startsWith('/')) key = key.slice(1);
  return decodeURIComponent(key);
}

function contentTypeForKey(key, httpMetadata) {
  const declared = httpMetadata && httpMetadata.contentType;
  if (declared) return declared;
  const dot = key.lastIndexOf('.');
  const ext = dot === -1 ? '' : key.slice(dot + 1).toLowerCase();
  return CONTENT_TYPES[ext] || DEFAULT_CONTENT_TYPE;
}

function basenameOf(key) {
  const slash = key.lastIndexOf('/');
  return slash === -1 ? key : key.slice(slash + 1);
}

/** Stream an object out of R2 without buffering it in the Worker. */
async function serveDownload(request, env) {
  if (!env || !env.DOWNLOADS) {
    return plainResponse(
      'download service is not configured: the R2 binding "DOWNLOADS" is missing.',
      503,
    );
  }

  const url = new URL(request.url);
  const key = objectKeyFromPath(url.pathname);
  if (!key) {
    return plainResponse('not found', 404);
  }

  const object = await env.DOWNLOADS.get(key);
  if (object === null) {
    return plainResponse('not found: ' + key, 404);
  }

  const headers = new Headers({
    'content-type': contentTypeForKey(key, object.httpMetadata),
    'content-length': String(object.size),
    'content-disposition': 'attachment; filename="' + basenameOf(key) + '"',
    'cache-control': 'public, max-age=3600',
  });
  if (object.httpEtag) {
    headers.set('etag', object.httpEtag);
  }

  return new Response(object.body, { status: 200, headers });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const host = url.hostname;

    // 1) API-ish paths always go to the origin, on every host.
    if (isApiPath(url.pathname)) {
      return fetch(request);
    }

    // 2) Android APK downloads, streamed from R2.
    if (DOWNLOAD_HOSTS.has(host)) {
      return serveDownload(request, env);
    }

    // 3) Personal homepage.
    if (PERSONAL_HOSTS.has(host)) {
      return htmlResponse(personalHomePage());
    }

    // 4) Product landing page.
    if (LANDING_HOSTS.has(host)) {
      return htmlResponse(projectLandingPage());
    }

    // 5) 控制台主机（ops./merchant./admin.）：静态外壳走边缘缓存，其余原样回源。
    if (CONSOLE_CACHE_HOSTS.has(host) && isConsoleCacheableRequest(request, url)) {
      try {
        return await serveConsoleWithEdgeCache(request, ctx);
      } catch (error) {
        // 缓存层任何异常都不能影响业务：直接回源。
        return fetch(request);
      }
    }

    // 6) Every other host (food./food-admin./identity./
    //    payment./wallet./commerce./agent. ...) is served by the K3s cluster.
    //    Pass through untouched.
    return fetch(request);
  },
};
