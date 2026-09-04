location ~* ^/(merchant|ops)/.+\.(?:js|css|jpg|jpeg|png|gif|svg|webp|ico|woff|woff2|ttf)$ {
    root /var/www/pay.su46proj.site;
    gzip_static on;
    expires 30d;
    add_header Cache-Control "public, max-age=2592000, immutable" always;
    add_header Cloudflare-CDN-Cache-Control "public, max-age=2592000, stale-while-revalidate=86400, stale-if-error=604800" always;
    try_files $uri =404;
}
