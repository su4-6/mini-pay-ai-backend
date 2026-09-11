FROM nginx:1.27-alpine
COPY unpackage/dist/build/h5-minipay/ /usr/share/nginx/html/
COPY docker/production-nginx.conf /etc/nginx/conf.d/default.conf
USER 101
EXPOSE 8080
