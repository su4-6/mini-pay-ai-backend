package co.yixiang.yshop.module.order.api.minipay;

import java.math.BigDecimal;

/** Integration port used by order administration without coupling order-biz to MiniPay. */
public interface MiniPayRefundApi {
    void requestApprovedFullRefund(String orderNo, BigDecimal amount, String reason);
}
