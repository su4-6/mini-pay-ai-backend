# MiniPay 外卖维护页镜像（变更 #47/#48）
#
# 用途：外卖模块临时下线时，food.su46proj.site 与 food-admin.su46proj.site
# 用它顶替原来的 503，给访问者一张说明页而不是错误页。
#
# 构建与上线（本机 Docker 可用时）：
#   docker build -f deploy/k3s/maintenance/Dockerfile -t suqihang/maintenance-page:0.1.1 deploy/k3s/maintenance
#   docker push  suqihang/maintenance-page:0.1.1
# 若本机 DNS 推不动 Docker Hub，改用节点本地导入：
#   docker save suqihang/maintenance-page:0.1.1 -o maintenance.tar
#   scp maintenance.tar shanghai-vm:/tmp/
#   ssh shanghai-vm 'sudo k3s ctr -n k8s.io images import /tmp/maintenance.tar'
#
# 恢复外卖：把 HTTPRoute 的 / 规则指回 yshop-food-h5 / yshop-admin-web，
# 并把 yshop-server / yshop-food-h5 / yshop-admin-web 三个 Deployment 拉回 1 副本。
FROM nginx:1.27-alpine
COPY index.html /usr/share/nginx/html/index.html
# 公安联网备案图标（页脚「图标 + 编号」用，静态资源与页面同域，避免额外域名请求）
COPY beian.png /usr/share/nginx/html/beian.png
# 用官方默认配置即可（监听 80，root 指向 /usr/share/nginx/html）
RUN printf 'server {\n  listen 8080;\n  server_name _;\n  root /usr/share/nginx/html;\n  index index.html;\n  location = /healthz { access_log off; return 200 "ok\\n"; }\n  location / { try_files $uri $uri/ /index.html; }\n}\n' > /etc/nginx/conf.d/default.conf
EXPOSE 8080
