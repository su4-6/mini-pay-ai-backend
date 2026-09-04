# 三仓库生产部署

## 1. 目录与前置条件

Linux 服务器安装 Docker Engine、Compose v2、JDK 21、Git、OpenSSL，并仅向公网开放
`22/80/443`。建议目录：

```text
/opt/minipay/backend
/opt/minipay/frontend
/opt/minipay/yshop
/opt/minipay/yshop-h5
```

DNS 必须提前解析 Identity、Payment、Wallet、Agent、Commerce、运营、管理、商户和外卖域名。
Caddy 自动申请 TLS 证书，因此 80/443 必须可从公网访问。

## 2. 构建外卖 H5

UniApp H5 依赖 HBuilderX 编译器，应先在构建机执行：

```powershell
cd D:\WorkSpace\yshop-drink\yshop-drink-uniapp-vue3
.\scripts\build-minipay-h5.ps1 -ApiBaseUrl "https://food.example.com/app-api"
```

将整个仓库（至少包含 `unpackage/dist/build/h5-minipay`）上传到服务器的
`YSHOP_H5_ROOT`。API 使用同源 `/app-api`，由 H5 Nginx 转发到 `yshop-server`。

## 3. 生成并填写生产环境

```bash
cd /opt/minipay/backend
bash scripts/bootstrap-server-env.sh
chmod 600 .env.production .secrets/jwt-*.pem
```

编辑 `.env.production`：替换全部 `example.com` 域名，填写短信、OSS、模型、高德地图配置，
并确认 `FRONTEND_ROOT`、`YSHOP_ROOT`、`YSHOP_H5_ROOT` 与服务器目录一致。禁止提交该文件和
`.secrets/`。

验证配置：

```bash
ENV_FILE=.env.production bash scripts/validate-production-env.sh
```

校验会拒绝空值、HTTP 公网地址、示例域名、演示口令、`demo-auth` 所需的演示配置以及不存在的
JWT 密钥文件；同时执行最终 Compose 合并解析。

## 4. 部署

```bash
cd /opt/minipay/backend
ENV_FILE=.env.production bash scripts/deploy-production-stack.sh
```

脚本按顺序构建并启动 MiniPay 基础设施和服务、yshop MySQL/Redis/API、三套 Web、外卖 H5，
最后启动 Caddy。数据库和 Redis 使用持久卷，脚本不会执行 `down -v`。

## 5. Android Release

复制 `frontend/android/gradle.production.properties.example` 中的属性到构建用户私有的
`~/.gradle/gradle.properties`，换成真实 HTTPS 地址和正式签名文件，再执行：

```bash
cd /opt/minipay/frontend/android
./gradlew assembleRelease
```

Release 构建缺少正式签名配置时会直接失败，不会再使用 Debug 证书。服务器环境不使用
`adb reverse`。

## 6. 验收与备份

```bash
docker compose --env-file .env.production -f compose.yaml -f compose.production.yml --profile apps ps
curl -fsS https://identity.example.com/actuator/health/readiness
curl -fsS https://payment.example.com/actuator/health/readiness
curl -fsS https://wallet.example.com/actuator/health/readiness
curl -fsS https://commerce.example.com/actuator/health/readiness
curl -fsS https://food.example.com/healthz
```

上线前必须另外验证 OAuth 回调、短信登录、外卖授权/HMAC、支付 TCC、RabbitMQ Outbox/Inbox、
对象存储上传以及数据库恢复演练。生产备份至少覆盖 MiniPay MySQL、yshop MySQL、Redis 持久卷、
Caddy 数据和 JWT/业务密钥；密钥备份应与数据备份分离保存。
