# 商户 B 端本地运行

## 前置条件

- JDK 21：`C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot`
- Maven 3.9+
- Docker Desktop
- Node.js 24 与 pnpm 10

项目 Maven Enforcer 不接受 JDK 26；请使用脚本或在当前 PowerShell 会话设置 JDK 21。

## 启动

```powershell
cd mini-pay-ai-backend
.\scripts\start-merchant-local.ps1 -Target infra
.\scripts\start-merchant-local.ps1 -Target payment
```

If Docker Hub images for MySQL 8.4 or Seata are unavailable locally, the checked-in
fallback uses the already available MySQL 8.0 image and intentionally leaves Seata
off. On this machine the MySQL host port is 3307:

```powershell
.\scripts\start-merchant-local.ps1 -Target infra -MysqlPort 3307 -UseLocalInfraFallback
.\scripts\start-merchant-local.ps1 -Target payment -MysqlPort 3307 -MysqlDatabase minipay_payment_smoke_20260805 -UseLocalInfraFallback
```

`minipay_payment` in the pre-existing local Docker volume has a historical Flyway
checksum mismatch, so use the isolated `minipay_payment_smoke_20260805` database
above on this machine. Do not run `flyway repair` or remove the existing volume to
work around it; a clean database can be supplied through `-MysqlDatabase`.

The payment target injects a development-only merchant-secret encryption key when
`MERCHANT_APP_SECRET_KEY` is absent. Do not use that key outside local development.

前端使用 `pnpm.cmd`，避免本机 PowerShell 的 `pnpm.ps1` 执行策略限制：

```powershell
cd mini-pay-ai-frontend
pnpm.cmd --filter @minipay/merchant-web dev
pnpm.cmd --filter @minipay/ops-web dev
```

商户与运营前端开发代理默认连接 `http://localhost:8088`（Management BFF）；BFF 再以服务端
Token 调用支付服务 `http://localhost:8082`。基础设施就绪后，可检查
`http://localhost:8082/actuator/health/readiness`。

若希望直接以 Docker 启动整套服务（而非单独调试 payment-service），使用：

```powershell
.\scripts\start-merchant-local.ps1 -Target apps
```

Docker Desktop 必须已启动并且 Linux daemon 可用；当前机器若看到
`//./pipe/dockerDesktopLinuxEngine` 不存在，请先启动 Docker Desktop，再执行上述命令。该脚本只会启动或构建服务，不会删除数据卷。

## 数据库安全

- Flyway 仅追加迁移，不能修改已运行的迁移。
- 启动脚本不会执行 `docker compose down -v`、删库或写入演示覆盖数据。
- 若需要全新沙箱，先手动备份并明确确认目标 Compose 项目和卷，再单独执行重置操作。
