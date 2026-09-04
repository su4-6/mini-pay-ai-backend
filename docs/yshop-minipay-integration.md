# yshop 外卖接入 MiniPay AI

## 边界

- yshop 保存门店、商品、库存、地址、报价、订单与履约真相。
- Commerce 保存 MiniPay/yshop 绑定、UUID 资源引用、AI 购物车、短时位置上下文、订单/支付关联及 Inbox/Outbox。
- Payment 保存支付单与退款单，并按 `FOOD_PLATFORM_MERCHANT_APP_ID` 解析统一平台商户。
- Wallet 独占余额、冻结、入账与复式账本。
- Agent 仅调用白名单工具；模型看不到经纬度、完整地址、手机号、Token 或支付凭据。

正式模式设置 `MINIPAY_FOOD_PROVIDER=yshop`。原沙箱接口继续保留用于开发测试，但不进入正式订单中心。

## 本地配置

MiniPay 后端：

```text
MINIPAY_FOOD_PROVIDER=yshop
MINIPAY_FOOD_H5_ORIGIN=https://food.minipay.local
YSHOP_INTERNAL_URL=http://host.docker.internal:48081
YSHOP_MINIPAY_HMAC_SECRET=<至少 32 字节且与 yshop 相同>
FOOD_PLATFORM_MERCHANT_APP_ID=<MiniPay 平台商户应用 app_id>
```

yshop：

```text
MINIPAY_TENANT_ID=<本地租户 ID>
MINIPAY_HMAC_SECRET=<与 Commerce 相同>
MINIPAY_COMMERCE_BASE_URL=http://localhost:8085
MINIPAY_H5_ORIGIN=https://food.minipay.local
```

Android 调试配置：

```text
MINIPAY_DEBUG_FOOD_H5_ORIGIN=https://food.minipay.local
MINIPAY_DEBUG_COMMERCE_BASE_URL=http://127.0.0.1:8085
```

所有正式 URL 必须为 HTTPS。调试域名需要由设备信任的开发 CA 签发证书，不允许放宽 WebView HTTPS、Origin 或混合内容策略。

## 主链路

1. Android 将高德 GCJ-02 坐标换成 5 分钟 `locationContextId`，Agent 只接收该 ID；定位不可用时由 Agent 展示脱敏 yshop 地址，选择后由 Commerce 在服务端使用该地址坐标查店。
2. AI 通过 `/internal/v2/agent/food/**` 查店、菜单、独立购物车、脱敏地址和权威报价。
3. AI 或 H5 使用 yshop 10 分钟报价幂等创建订单。
4. Commerce 写入 `commerce.food-order.created` v2；Payment 创建统一平台商户支付单。
5. Android 获取服务端金额，向 Identity 申请一次性支付授权并确认钱包支付。
6. Payment Outbox 经 RabbitMQ 到 Commerce Inbox，再由签名回调最终推进 yshop。

H5 只向 Bridge 发送 `externalOrderNo`。Android 不接受 H5 金额；Commerce 会重新读取订单归属、金额、状态和期限。

## 数据库升级

- Commerce：依次执行 Flyway `V4__add_yshop_food_provider.sql`、`V5__allow_h5_food_orders.sql`。
- yshop：执行 `sql/migrations/minipay/V001__minipay_food_integration.sql`，不得修改原始 SQL 转储。

## 发布前检查

- 配置真实平台商户应用，验证消费者扣款与平台商户入账。
- 验证 HMAC 密钥轮换、Nonce 防重放、Outbox 重试与 DLQ 告警。
- 确认 yshop 源码授权范围；仓库部分源码头包含购买限制说明。
