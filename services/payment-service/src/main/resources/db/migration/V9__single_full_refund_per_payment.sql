ALTER TABLE refund_order
  DROP INDEX uk_refund_order_payment_merchant_refund,
  ADD UNIQUE KEY uk_refund_order_payment (pay_order_id);
