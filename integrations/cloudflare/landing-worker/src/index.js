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

    // 5) Every other host (ops./merchant./admin./food./food-admin./identity./
    //    payment./wallet./commerce./agent. ...) is served by the K3s cluster.
    //    Pass through untouched.
    return fetch(request);
  },
};
