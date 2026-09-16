-- 归一化历史外卖订单的支付状态词表。
--
-- 背景：领域枚举 PaymentStatus 只有 UNPAID / SUCCEEDED / FAILED，
-- 但历史（旧版本代码或旧演示数据）在 food_order.payment_status 里写过
-- PAID / REFUNDED / PROCESSING。读取这些行时 PaymentStatus.valueOf 会抛
-- IllegalArgumentException：SandboxFulfillmentScheduler 每 5 秒失败一次，
-- 订单查询接口也会整表读不出来（线上实测）。
--
-- 语义对应：订单是否已收款看 payment_status（PAID/REFUNDED 都是收过款 → SUCCEEDED），
-- 退款进度由 refund_status 单独表达。
UPDATE food_order SET payment_status = 'SUCCEEDED'
 WHERE payment_status IN ('PAID', 'REFUNDED', 'PROCESSING');

UPDATE food_order SET payment_status = 'UNPAID'
 WHERE payment_status IS NULL
    OR payment_status NOT IN ('UNPAID', 'SUCCEEDED', 'FAILED');
