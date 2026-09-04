# MiniPay 本地运行说明

本文用于让新开发者在 Windows 或 Linux 上获得与当前开发机一致的 MiniPay 本地环境。业务源码保留在宿主机；Docker 负责统一 MySQL、Redis、RabbitMQ、Seata 以及后端服务运行环境。

## 1. 前置软件

- Git
- Docker Desktop（Windows/macOS）或 Docker Engine（Linux）
- Docker Compose v2，执行 `docker compose version` 可检查
- JDK 21
- Node.js 20+、Corepack、pnpm 10.34.5
- Android Studio / Android SDK（仅 Android 调试需要）
- HBuilderX（仅重新构建外卖 H5 时需要）

确认 Docker 已启动：

```bash
docker info
docker compose version
```

## 2. 目录结构

三个仓库必须放在同一个父目录中，因为统一 Compose 使用相对路径挂载 yshop JAR 和外卖 H5：

```text
java/
├── mini-pay-ai-backend/
├── mini-pay-ai-frontend/
└── yshop-drink/
```

团队成员应检出相同分支和 Git commit。可使用下面的命令记录当前版本：

```bash
git rev-parse --short HEAD
```

## 3. 本地环境变量

进入后端仓库，首次运行时复制示例文件：

Windows PowerShell：

```powershell
Set-Location C:\java\mini-pay-ai-backend
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Linux/macOS：

```bash
cd /path/to/java/mini-pay-ai-backend
test -f .env || cp .env.example .env
```

默认值用于本地沙箱。模型 Key、短信/OSS AK/SK、真实密码和证书不得提交 Git，应通过团队密码管理工具单独分发并写入被 Git 忽略的 `.env`。

## 4. 首次构建后端

Windows：

```powershell
.\mvnw.cmd -B -ntp verify
```

Linux/macOS：

```bash
./mvnw -B -ntp verify
```

该命令会完成所有模块编译、测试、数据库迁移测试，并在各服务的 `target/` 下生成 JAR。

## 5. 启动中间件

只启动 MySQL、Redis、RabbitMQ 和 Seata：

```bash
docker compose -f docker-compose.yml up -d mysql redis rabbitmq seata-server
```

检查状态：

```bash
docker compose -f docker-compose.yml ps
```

固定连接信息：

| 服务 | 本机地址 | 用户名 | 默认密码 |
|---|---|---|---|
| MySQL | `localhost:3306` | `minipay` | `minipay` |
| MySQL 管理账号 | `localhost:3306` | `root` | `minipay` |
| Redis | `localhost:6379` | - | - |
| RabbitMQ | `localhost:5672` | `minipay` | `minipay` |
| RabbitMQ 控制台 | `http://localhost:15672` | `minipay` | `minipay` |
| Seata TC | `localhost:8091` | - | - |

## 6. 启动 MiniPay 后端

构建并启动 8 个 Java 服务及依赖：

```bash
docker compose -f compose.yaml --profile apps up -d --build
```

使用 `compose.yaml` 可以只启动 MiniPay 后端，不要求本机已经准备 yshop/H5 产物。

服务端口：

| 服务 | 端口 |
|---|---:|
| Identity | 8081 |
| Payment | 8082 |
| Wallet | 8083 |
| Commerce | 8085 |
| Agent | 8086 |
| Consumer BFF | 8087 |
| Management BFF | 8088 |
| Admin BFF | 8089 |
| Callback Mock | 8098 |

等待健康检查：

```bash
docker compose -f compose.yaml --profile apps ps
```

健康探测示例：

```bash
curl http://localhost:8081/actuator/health/readiness
curl http://localhost:8082/actuator/health/readiness
curl http://localhost:8083/actuator/health/readiness
curl http://localhost:8085/actuator/health/readiness
curl http://localhost:8086/actuator/health/readiness
```

## 7. 启动 yshop 和外卖 H5

先构建 yshop JAR。Windows：

```powershell
.\mvnw.cmd -B -ntp `
  -f ..\yshop-drink\yshop-drink-boot3\pom.xml `
  -pl yshop-server -am -DskipTests package
```

Linux/macOS：

```bash
./mvnw -B -ntp \
  -f ../yshop-drink/yshop-drink-boot3/pom.xml \
  -pl yshop-server -am -DskipTests package
```

外卖 H5 产物必须位于：

```text
../yshop-drink/yshop-drink-uniapp-vue3/unpackage/dist/build/h5-minipay/
```

如果该目录不存在，使用 HBuilderX 构建 H5。准备好 JAR 与 H5 后启动：

```bash
docker compose -f docker-compose.yml up -d yshop-bridge food-h5
```

入口：

- yshop API：`http://localhost:48081`
- 外卖 H5：`http://localhost:4173`

## 8. 启动 Web 前端

进入前端仓库并安装依赖：

```bash
cd ../mini-pay-ai-frontend
corepack enable
pnpm install --frozen-lockfile
```

建议在四个终端分别运行，避免端口冲突：

Windows PowerShell：

```powershell
$env:PORT=8000; pnpm --filter @minipay/consumer-h5 dev
$env:PORT=8001; pnpm --filter @minipay/merchant-web dev
$env:PORT=8002; pnpm --filter @minipay/admin-web dev
$env:PORT=8003; pnpm --filter @minipay/ops-web dev
```

Linux/macOS：

```bash
PORT=8000 pnpm --filter @minipay/consumer-h5 dev
PORT=8001 pnpm --filter @minipay/merchant-web dev
PORT=8002 pnpm --filter @minipay/admin-web dev
PORT=8003 pnpm --filter @minipay/ops-web dev
```

提交前可执行完整验证：

```bash
pnpm verify
```

## 9. Android 真机调试

在 `mini-pay-ai-frontend/android/local.properties` 中配置本机 Android SDK。Debug API 默认使用手机的 `127.0.0.1`，通过 USB 映射到开发机：

```properties
sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
MINIPAY_DEBUG_IDENTITY_BASE_URL=http://127.0.0.1:8081
MINIPAY_DEBUG_PAYMENT_BASE_URL=http://127.0.0.1:8082
MINIPAY_DEBUG_WALLET_BASE_URL=http://127.0.0.1:8083
MINIPAY_DEBUG_AGENT_BASE_URL=http://127.0.0.1:8086
MINIPAY_DEBUG_FOOD_H5_ORIGIN=http://127.0.0.1:4173
```

连接手机、开启 USB 调试并确认设备：

```bash
adb devices -l
```

配置端口映射：

```bash
adb reverse tcp:4173 tcp:4173
adb reverse tcp:48081 tcp:48081
adb reverse tcp:8081 tcp:8081
adb reverse tcp:8082 tcp:8082
adb reverse tcp:8083 tcp:8083
adb reverse tcp:8085 tcp:8085
adb reverse tcp:8086 tcp:8086
adb reverse tcp:8087 tcp:8087
```

构建并安装 Debug APK：

Windows：

```powershell
Set-Location ..\mini-pay-ai-frontend\android
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true'
.\gradlew.bat --no-daemon :app:installDebug
```

Linux/macOS：

```bash
cd ../mini-pay-ai-frontend/android
JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true ./gradlew --no-daemon :app:installDebug
```

小米手机如果返回 `INSTALL_FAILED_USER_RESTRICTED`，请保持手机解锁，在开发者选项中开启“USB 安装”，并在安装弹窗中确认。

USB 断开或手机重启后，需要重新执行 `adb reverse`。

## 10. 修改代码后的更新方式

后端单服务示例：

```bash
./mvnw -B -ntp -pl services/commerce-service -am -DskipTests package
docker compose -f compose.yaml --profile apps up -d --build commerce-service
```

yshop：

```bash
docker compose -f docker-compose.yml restart yshop-bridge
```

Android：重新执行 `:app:installDebug`，会覆盖安装并保留允许保留的应用数据。

## 11. 日常管理

查看状态：

```bash
docker compose -f compose.yaml --profile apps ps
```

查看指定服务日志：

```bash
docker compose -f compose.yaml logs -f commerce-service
```

重启指定服务：

```bash
docker compose -f compose.yaml restart commerce-service
```

停止并保留数据：

```bash
docker compose -f compose.yaml --profile apps down
```

不要在日常开发中执行 `down -v`。`-v` 会删除 MySQL、Redis 和 RabbitMQ 数据卷。

## 12. 常见问题

### 端口已占用

检查 3306、5672、6379、7091、8091、8081–8089、4173 和 48081 是否被其他程序占用。团队环境应统一使用标准端口，不建议每个人自行修改端口。

### Docker 服务启动失败

```bash
docker compose -f compose.yaml logs --tail=200 服务名
docker inspect 容器名
```

### App 无法访问后端

依次确认：

1. `adb devices -l` 显示状态为 `device`；
2. `adb reverse --list` 包含对应端口；
3. 后端 readiness 返回 HTTP 200；
4. 手机没有断开 USB 或撤销 USB 调试授权。

### Gradle 下载 Google 依赖失败

项目已优先配置国内 Maven 镜像，并保留官方仓库回退。若仍失败，确认公司代理、防火墙和 DNS 配置，不要随意更改依赖版本。

### 需要完全一致的演示数据

Compose 只统一软件、端口和配置，不会把个人数据库内容提交到镜像。需要共享数据时，应使用经过脱敏的 MySQL 备份，通过单独受控渠道分发。
