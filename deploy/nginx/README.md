# MiniPay nginx performance configuration

These files mirror the production configuration used by `pay.su46proj.site`.
They keep the APK and fingerprinted web assets cacheable for 30 days, serve
pre-compressed `.gz` bundles, tune nginx file delivery, and allow the
application's supported 5 MiB shop-image uploads through the gateway.

- Load `minipay-performance.conf` from the nginx `http` context.
- Include `minipay-mobile-api.conf`, `minipay-web-cache-locations.inc`, and
  `minipay-admin-cache-location.inc` from the public HTTPS server block.
- Keep `/downloads/MiniPay.apk` as the Cloudflare-safe public download URL and
  point it to the current versioned APK. `/apk` is the origin-side short alias
  that redirects to it without caching.
- Run `nginx -t` before reloading nginx.
- Do not cache API, OAuth, login, logout, or HTML responses at the CDN edge.

Cloudflare must also have a Cache Rule that marks `/downloads/*` and static
asset extensions as eligible for cache. Origin cache headers cannot override a
zone-wide bypass rule.
