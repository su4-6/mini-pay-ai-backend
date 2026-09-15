#!/usr/bin/env bash
# MiniPay · NGF（nginx-gateway-fabric）资源与滚动策略固化（变更 #48）
#
# 为什么需要单独跑一次：控制面 Deployment（namespace nginx-gateway）不在我们的
# kustomize 树里（它是 NGF 安装清单创建的），无法用 overlay patch；数据面的
# resources 已经通过 NginxProxy（deploy/k3s/base/config/nginx-proxy.yaml +
# overlays/*/gateway.yaml 的 parametersRef）声明，这里只补两件 NGF 不暴露的东西：
#   1) 控制面的 resources 与滚动策略（单副本：先停后起，避免两份控制面同时占内存）
#   2) 数据面的滚动策略（先起新、就绪后再停旧 —— 网关绝不能出现"无就绪 pod"的窗口；
#      变更 #46 我用 maxSurge=0 导致过一次全站 521，这里是反向修正）
#
# 幂等：可重复执行。用法（在服务器上）：
#   sudo bash scripts/k3s/apply-ngf-tuning.sh
set -euo pipefail

# 注意：containers 是列表，必须用 --type=strategic。
# merge patch 会把整个列表替换掉，丢掉 image 字段并被 API 拒绝（本次部署踩过，见变更 #48）。
echo "== 1/2 控制面 nginx-gateway =="
kubectl -n nginx-gateway patch deploy nginx-gateway --type=strategic -p '{
  "spec": {
    "strategy": {"type": "RollingUpdate", "rollingUpdate": {"maxSurge": 0, "maxUnavailable": 1}},
    "template": {"spec": {"containers": [{
      "name": "nginx-gateway",
      "resources": {
        "requests": {"cpu": "50m", "memory": "64Mi"},
        "limits": {"cpu": "500m", "memory": "192Mi"}
      }
    }]}}
  }
}'

# 数据面（minipay-gateway-nginx）**不需要**在这里 patch：
#   - resources 由 NginxProxy 声明（deploy/k3s/base/config/nginx-proxy.yaml +
#     overlays/*/gateway.yaml 的 infrastructure.parametersRef），NGF 每次 reconcile 都按它渲染；
#   - strategy 不在 NginxProxy schema 里，patch 上去会被 NGF 的下一次 reconcile 覆盖
#     （实测被还原成默认 25%/25%）。而 NGF 默认值在 replicas=1 时等价于
#     maxSurge=1 / maxUnavailable=0 —— 先起新 pod、就绪后再停旧，网关不会出现无就绪窗口，
#     正是我们想要的行为，因此不再干预。

echo "== 2/2 数据面资源来源确认（NginxProxy，只读检查）=="
kubectl -n minipay get nginxproxy minipay-gateway-proxy \
  -o jsonpath='  NginxProxy 声明: requests={.spec.kubernetes.deployment.container.resources.requests} limits={.spec.kubernetes.deployment.container.resources.limits}{"\n"}' 2>/dev/null || echo "  (未找到 NginxProxy)"

echo "== 结果 =="
kubectl -n nginx-gateway get deploy nginx-gateway \
  -o jsonpath='控制面: replicas={.spec.replicas} limits={.spec.template.spec.containers[0].resources.limits} strategy={.spec.strategy.rollingUpdate}{"\n"}'
kubectl -n minipay get deploy minipay-gateway-nginx \
  -o jsonpath='数据面: replicas={.spec.replicas} limits={.spec.template.spec.containers[0].resources.limits} strategy={.spec.strategy.rollingUpdate}{"\n"}'
kubectl -n minipay get gateway minipay-gateway \
  -o jsonpath='Gateway: {range .status.conditions[*]}{.type}={.status} {end}{"\n"}'
