# 阿里云 OSS 与短信真实渠道配置

Identity Service 负责验证码发送和用户头像对象存储；Payment Service 负责商户/店铺图片对象存储。真实渠道仅在显式启用时生效：

- `SMS_PROVIDER=dypns`：使用阿里云**号码认证服务** `SendSmsVerifyCode` 接口（个人开发者可用，无需申请短信签名/模板，验证码由阿里云生成并返回）。
- `OBJECT_STORAGE_PROVIDER=aliyun`：使用私有 OSS Bucket 和 V4 短时预签名 URL（identity 头像 + payment 店铺图共用）。
- `CONTENT_SAFETY_PROVIDER=aliyun`：头像和昵称同时使用阿里云内容安全审核；它不是 OSS 上传的必要条件，但生产环境建议启用。

未显式启用时不会调用阿里云。`demo` 仅允许本地 `demo-auth` Profile 使用；签名、模板、Endpoint、Region 或 Bucket 等必填渠道参数不完整时服务启动失败，不会回退到演示验证码。云上角色和动态凭证可能延迟到首次请求时解析，解析失败会返回可重试的服务不可用错误。

## 1. 阿里云侧准备

### 短信（号码认证 dypns）

1. 开通阿里云**号码认证服务**（个人开发者可用，无需企业资质）。
2. 在号码认证控制台查看签名名称（如「恒创联众」）。
3. 使用系统模板 `100001`（占位符 `##code##`，勿改），验证码由阿里云生成，通过 `returnVerifyCode=true` 返回给服务端。
4. 为使用的 RAM 身份只授予号码认证发送短信所需权限。不要使用主账号 AccessKey。

### OSS

1. 创建与 Identity 部署地域匹配的私有 Bucket，禁止公共读写。
2. RAM 身份只允许访问该 Bucket 的 `avatars/*` 前缀，并授予上传、读取对象元信息、读取和删除对象所需权限。
3. Consumer Web/H5 直传时配置 Bucket CORS：只允许实际前端 Origin；允许 `PUT/GET/HEAD`；允许 `Content-Type` 和 `x-oss-meta-sha256` 请求头；可暴露 `ETag`。不要在生产配置中使用 `*` Origin。
4. `ALIYUN_OSS_ENDPOINT` 使用 Bucket 所在地域的 HTTPS Endpoint，例如 `https://oss-cn-hangzhou.aliyuncs.com`；`ALIYUN_OSS_REGION` 使用对应 Region ID，例如 `cn-hangzhou`。

Identity 只把短期 PUT/GET URL 返回给客户端，不返回 AccessKey。上传 URL 会把内容类型和 SHA-256 元数据纳入签名；完成资料更新前，服务端会再次读取对象元信息并做大小、类型和摘要校验。

## 2. 本地 `.env` 配置

复制 `.env.example` 后填写以下值。`.env` 已被 Git 忽略，禁止把真实值写入 `.env.example`、YAML、日志或截图。本地 JVM（IDEA 直接跑）由各服务 demo yml 的 `spring.config.import` 自动读取项目根目录 `.env`；docker compose 启动则自动注入容器。

```dotenv
# 短信（号码认证 dypns）
SMS_PROVIDER=dypns
ALIYUN_DYPNS_SIGN_NAME=号码认证控制台的签名
ALIYUN_DYPNS_TEMPLATE_CODE=100001
ALIYUN_DYPNS_TEMPLATE_PARAM={"code":"##code##","min":"5"}

# OSS（identity 头像 + payment 店铺图共用）
OBJECT_STORAGE_PROVIDER=aliyun
ALIYUN_OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
ALIYUN_OSS_REGION=cn-beijing
ALIYUN_OSS_BUCKET=你的私有Bucket名称

# AK/SK：短信和 OSS 共用，配一组即可。
# 优先使用 OSS 专用变量（ALIYUN_OSS_ACCESS_KEY_*），未设置时自动回退到 ALIBABA_CLOUD_*。
# 仅本地开发使用RAM用户AK；不要使用阿里云主账号AK。
ALIBABA_CLOUD_ACCESS_KEY_ID=
ALIBABA_CLOUD_ACCESS_KEY_SECRET=
```

如果还要启用真实内容审核：

```dotenv
CONTENT_SAFETY_PROVIDER=aliyun
ALIYUN_GREEN_ENDPOINT=green-cip.cn-shanghai.aliyuncs.com
ALIYUN_GREEN_REGION=cn-shanghai
ALIYUN_GREEN_TEXT_SERVICE=nickname_detection
ALIYUN_GREEN_IMAGE_SERVICE=baselineCheck
```

## 3. 云上凭证方式

程序使用阿里云官方默认凭证链，并支持临时凭证自动刷新。优先使用以下任一方式，避免长期 AccessKey：

- ECS/ECI 实例 RAM Role：设置 `ALIBABA_CLOUD_ECS_METADATA` 为绑定的角色名，或让默认凭证链从实例元数据发现角色。
- ACK RRSA/OIDC：设置 `ALIBABA_CLOUD_ROLE_ARN`、`ALIBABA_CLOUD_OIDC_PROVIDER_ARN` 和容器内可读的 `ALIBABA_CLOUD_OIDC_TOKEN_FILE`。
- 凭证 URI：设置 `ALIBABA_CLOUD_CREDENTIALS_URI`，由可信的本地凭证代理返回 STS 临时凭证。
- 临时 STS：同时设置 `ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET`、`ALIBABA_CLOUD_SECURITY_TOKEN`。

Compose 只负责把这些变量传给 Identity 容器。OIDC Token 文件需要通过部署平台或 Compose override 挂载到容器，不能提交到仓库。

## 4. 手动启动和验证

```powershell
docker compose up -d mysql-core redis rabbitmq seata-server
docker compose --profile apps up --build identity-service
```

启动日志不应出现手机号、验证码、AccessKey 或预签名 URL。手工验证：

1. 从 Consumer 登录页填写一个可接收短信的大陆手机号并获取验证码，确认手机收到号码认证短信（验证码由阿里云生成，服务端不参与内容）。
2. 输入收到的验证码完成登录；错误验证码、过期验证码和频繁重发仍由现有 Redis 原子校验与限流保护。
3. 在资料页选择头像，确认客户端取得短时 PUT URL、直传到私有 Bucket，并在提交资料后得到短时读取 URL；OSS 控制台可看到 `avatars/*` 对象。
4. 将签名或模板临时改为错误值时，请求应返回 `503 SMS_DELIVERY_UNAVAILABLE`；恢复配置后再试。不要在生产号码上反复做失败测试。

官方资料：

- [阿里云号码认证服务文档](https://help.aliyun.com/zh/dypns/)
- [阿里云 Java SDK 默认凭证链](https://help.aliyun.com/en/sdk/developer-reference/v2-manage-access-credentials)
- [OSS Java SDK 预签名上传](https://help.aliyun.com/en/oss/developer-reference/upload-an-object-using-a-signed-url-generated-with-oss-sdk-for-java)
