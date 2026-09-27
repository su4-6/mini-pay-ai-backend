const STATIC_EXTENSIONS = new Set([
  "js", "css", "woff", "woff2", "ttf", "svg", "png", "jpg", "jpeg",
  "webp", "gif", "ico",
]);

// Public hostname of the pay portal. This constant used to be named APK_HOST
// because it also streamed the Android package from R2; the Android client is
// retired, so the APK download path and the R2 binding were removed and the name
// corrected. See android/RETIRED.md in the frontend repository.
const PAY_HOST = "pay.su46proj.site";
const FOOD_H5_HOST = "food.su46proj.site";
const CALLBACK_HOST = "callback.su46proj.site";
const FRONTEND_RELEASE = "20260810-2";
const B_FRONTEND_RELEASE = "20260811-b-dc9d3929-ops-empty1";

const ORIGIN_PREFIXES = [
  "/merchant/switch-login",
  "/ops/switch-login",
  "/switch-login",
  "/api/", "/identity", "/oauth2/", "/login/oauth2/", "/logout",
  "/payment", "/wallet", "/agent", "/commerce", "/admin-bff",
  "/management", "/callback", "/food-api/", "/food-admin", "/_AMapService/",
];

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.hostname === CALLBACK_HOST) {
      return proxyCallbackToPayOrigin(request);
    }

    if (url.hostname === FOOD_H5_HOST && (request.method === "GET" || request.method === "HEAD")) {
      return serveFoodH5(request, env);
    }

    // Identity uses a relative /login entry point. On the public 443 portal it
    // belongs to ops; only the explicit 8443 origin is the admin login.
    if (url.hostname === PAY_HOST && url.pathname === "/login" &&
        url.port !== "8443" && (request.method === "GET" || request.method === "HEAD")) {
      const target = new URL(request.url);
      target.pathname = "/ops/login";
      return Response.redirect(target.toString(), 302);
    }

    if (url.hostname === PAY_HOST && shouldServeFromAssets(request, url)) {
      return serveFrontendAsset(request, env);
    }

    return proxyTo8443(request, ctx);
  },
};

async function proxyCallbackToPayOrigin(request) {
  const publicUrl = new URL(request.url);
  const originUrl = new URL(request.url);
  originUrl.protocol = "https:";
  originUrl.hostname = PAY_HOST;
  originUrl.port = "8443";
  originUrl.pathname = `/__callback${publicUrl.pathname}`;

  const headers = new Headers(request.headers);
  headers.set("X-Forwarded-Host", CALLBACK_HOST);
  headers.set("X-Forwarded-Proto", "https");
  headers.set("X-MiniPay-Edge-Callback", "1");
  const originResponse = await fetch(new Request(originUrl.toString(), {
    method: request.method,
    headers,
    body: request.body,
    redirect: "manual",
  }));
  const response = new Response(originResponse.body, originResponse);
  response.headers.set("X-MiniPay-Callback-Worker", "8443");
  return response;
}

function shouldServeFromAssets(request, url) {
  if (request.method !== "GET" && request.method !== "HEAD") return false;
  if (isOriginPath(url.pathname)) return false;
  const extension = url.pathname.includes(".") ? url.pathname.split(".").pop().toLowerCase() : "";
  if (STATIC_EXTENSIONS.has(extension)) return true;
  return request.headers.get("Accept")?.includes("text/html") === true;
}

function isOriginPath(pathname) {
  return ORIGIN_PREFIXES.some((prefix) => prefix.endsWith("/")
    ? pathname.startsWith(prefix)
    : pathname === prefix || pathname.startsWith(`${prefix}/`));
}

async function serveFrontendAsset(request, env) {
  const publicUrl = new URL(request.url);
  const assetUrl = new URL(request.url);
  const indexPath = frontendIndexPath(publicUrl.pathname);
  if (indexPath && request.headers.get("Accept")?.includes("text/html")) {
    assetUrl.pathname = indexPath;
    const release = ["/consumer/", "/food-h5/", "/food/"].includes(indexPath)
      ? FRONTEND_RELEASE
      : B_FRONTEND_RELEASE;
    assetUrl.search = `?release=${release}`;
  }
  const response = await env.ASSETS.fetch(new Request(assetUrl.toString(), request));
  return withAssetCacheHeaders(response, assetUrl.pathname);
}

async function serveFoodH5(request, env) {
  const publicUrl = new URL(request.url);
  const assetUrl = new URL(request.url);
  assetUrl.hostname = PAY_HOST;
  const extension = publicUrl.pathname.includes(".") ? publicUrl.pathname.split(".").pop().toLowerCase() : "";
  assetUrl.pathname = STATIC_EXTENSIONS.has(extension)
    ? (publicUrl.pathname.startsWith("/food-h5/")
        ? publicUrl.pathname
        : `/food-h5${publicUrl.pathname}`)
    : "/food-h5/";
  assetUrl.search = `?release=${FRONTEND_RELEASE}`;
  const assetRequest = new Request(assetUrl.toString(), request);
  const response = await env.ASSETS.fetch(assetRequest);
  return withAssetCacheHeaders(response, assetUrl.pathname);
}

function frontendIndexPath(pathname) {
  if (pathname === "/merchant" || pathname.startsWith("/merchant/")) return "/merchant/";
  if (pathname === "/ops" || pathname.startsWith("/ops/")) return "/ops/";
  if (pathname === "/consumer" || pathname.startsWith("/consumer/")) return "/consumer/";
  if (pathname === "/food-h5" || pathname.startsWith("/food-h5/")) return "/food-h5/";
  if (pathname === "/food" || pathname.startsWith("/food/")) return "/food/";
  return "/";
}

function withAssetCacheHeaders(original, pathname) {
  const response = new Response(original.body, original);
  const extension = pathname.includes(".") ? pathname.split(".").pop().toLowerCase() : "";
  if (STATIC_EXTENSIONS.has(extension)) {
    response.headers.set("Cache-Control", "public, max-age=604800, s-maxage=2592000, immutable");
  } else if (response.headers.get("Content-Type")?.includes("text/html")) {
    // SPA shells must never be retained under deep client-side routes. Caching
    // them can mix an older app shell with newer hashed chunks after deploys,
    // which appears as a blank page on refresh. Hashed JS/CSS remain immutable.
    response.headers.set("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate");
    response.headers.set("CDN-Cache-Control", "no-store");
    response.headers.set("Vary", "Accept");
  }
  response.headers.set("X-MiniPay-Edge", "worker-assets");
  return response;
}

async function proxyTo8443(request, ctx) {
  const publicUrl = new URL(request.url);
  const originUrl = new URL(request.url);
  originUrl.port = "8443";

  const isPayHost = publicUrl.hostname === PAY_HOST;
  const extension = publicUrl.pathname.split(".").pop().toLowerCase();
  const isStatic = STATIC_EXTENSIONS.has(extension);
  const acceptsHtml = request.headers.get("Accept")?.includes("text/html") === true;
  const isWebShell = acceptsHtml && (
    publicUrl.pathname === "/merchant" || publicUrl.pathname.startsWith("/merchant/") ||
    publicUrl.pathname === "/ops" || publicUrl.pathname.startsWith("/ops/")
  );
  const shouldCache = isPayHost && request.method === "GET" && (isStatic || isWebShell);
  const originRequest = new Request(originUrl.toString(), request);

  if (!shouldCache) {
    return fetch(originRequest);
  }

  const cache = caches.default;
  const cacheKeyUrl = new URL(publicUrl);
  if (isWebShell) {
    // Rotate the internal key when the shell caching policy changes so an old
    // browser TTL cannot survive a Worker deployment in caches.default.
    cacheKeyUrl.searchParams.set("__minipay_shell_v", "20260809-3");
  }
  const cacheKey = new Request(cacheKeyUrl.toString(), request);
  const cached = await cache.match(cacheKey);
  if (cached) {
    const hit = new Response(cached.body, cached);
    if (isWebShell) {
      // Keep only the explicit Cache API copy. Do not let Cloudflare's outer
      // cache or the browser retain the SPA shell after this Worker runs.
      hit.headers.set("Cache-Control", "no-cache, max-age=0, must-revalidate");
      hit.headers.set("CDN-Cache-Control", "no-store");
    }
    hit.headers.set("X-Worker-Cache", "HIT");
    return hit;
  }

  const originResponse = await fetch(originRequest);
  const response = new Response(originResponse.body, originResponse);
  if (originResponse.status !== 200 || originResponse.headers.has("Set-Cookie")) {
    response.headers.set("X-Worker-Cache", "BYPASS");
    return response;
  }

  const cacheResponse = response.clone();
  cacheResponse.headers.delete("Set-Cookie");
  if (isWebShell && originResponse.headers.get("Content-Type")?.includes("text/html")) {
    // The merchant and ops HTML files are static SPA shells. Revalidate in the
    // browser while keeping a short edge copy so navigation no longer waits on 8443.
    cacheResponse.headers.set("Cache-Control", "public, max-age=0, s-maxage=600");
    response.headers.set("Cache-Control", "no-cache, max-age=0, must-revalidate");
    response.headers.set("CDN-Cache-Control", "no-store");
  }
  ctx.waitUntil(cache.put(cacheKey, cacheResponse));
  response.headers.set("X-Worker-Cache", "MISS");
  return response;
}
