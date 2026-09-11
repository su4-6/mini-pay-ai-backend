FROM nginx:1.27-alpine
COPY dist-k3s/ /usr/share/nginx/html/
COPY .k3s-static-nginx.conf /etc/nginx/conf.d/default.conf
USER 101
EXPOSE 8080
