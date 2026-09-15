# 系统管理员平台本地启动

系统管理员平台与运营端、商户端会话完全独立。本地默认地址如下：

- 管理员 Web：`http://localhost:8002/`
- Admin BFF：`http://localhost:8089/`
- Identity：`http://localhost:8081/`

## 首位超级管理员

首次启动、且数据库中尚无 `system_super_admin` 时，在 PowerShell 中设置一个未绑定的 11 位手机号：

```powershell
$env:BOOTSTRAP_SYSTEM_ADMIN_MOBILE='18800009999'
$env:BOOTSTRAP_SYSTEM_ADMIN_DISPLAY_NAME='Local Super Admin'
docker compose --profile apps up -d --build
```

初始化是幂等的：已有超级管理员时不会重复创建，也不会覆盖现有账号。手机号和验证码不得写入代码、迁移或日志。

本地 `demo-auth` 环境使用短信登录，固定验证码为 `123456`。生产环境必须配置真实短信提供商，不能启用 `demo-auth`。

## 启动管理员 Web

开发模式：

```powershell
pnpm install --frozen-lockfile
$env:PORT='8002'
pnpm --filter @minipay/admin-web dev
```

Docker/Nginx 模式：

```powershell
# 早期用根目录 compose.server.yaml 在宿主机起 admin-web 容器的方式已随 K3s 迁移删除；
# 现在前端以静态镜像跑在 K3s 里，本地调试直接用上面那行 pnpm dev，或：
docker build -f ../mini-pay-ai-frontend/docker/k3s-web.Dockerfile --build-arg APP=admin-web -t suqihang/admin-web:local ../mini-pay-ai-frontend
```

打开 `http://localhost:8002/`，页面会跳转到 Identity 的短信登录。首位超级管理员登录后，可在“后台账号”中创建其他管理员或运营账号。

## 系统角色

| 角色 | 权限 |
|---|---|
| `system_super_admin` | 全局只读、后台账号与安全状态管理 |
| `system_account_admin` | 消费者、商户所有人和运营账号安全管理；不能管理系统管理员 |
| `system_auditor` | 账号、订单、钱包和审计只读 |

管理员不能修改余额、删除账本、代发退款、修改订单状态，也不具备运营端的入驻审核和商户经营权限。

## 健康检查

```powershell
docker compose --profile apps ps identity-service payment-service wallet-service admin-bff
Invoke-WebRequest -UseBasicParsing http://localhost:8089/api/v1/session
```

未登录会话应返回 `authenticated: false`；管理员登录后应返回脱敏的管理员身份和系统角色。
