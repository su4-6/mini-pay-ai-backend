INSERT INTO merchant (
  merchant_id, merchant_no, name, short_name, contact_name, contact_mobile,
  contact_email, remark, status, owner_user_id, version, created_at, updated_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')), 'M202608030001', '星河便利店', '星河便利', '演示联系人甲', '13900000001', 'demo1@example.com', '多商户店主的便利店', 'ACTIVE', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000002', '-', '')), 'M202608030002', '云帆咖啡', '云帆咖啡', '演示联系人乙', '13800000002', 'demo2@example.com', NULL, 'DISABLED', NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000003', '-', '')), 'M202608030003', '青禾书店', '青禾书店', '演示联系人丙', '13800000003', NULL, NULL, 'ACTIVE', NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000004', '-', '')), 'M202608030004', '远山餐厅', '远山餐厅', '演示联系人丁', '13900000001', 'demo4@example.com', '多商户店主的餐厅，存在交易依赖', 'ACTIVE', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  name = VALUES(name), short_name = VALUES(short_name), contact_name = VALUES(contact_name),
  contact_mobile = VALUES(contact_mobile), contact_email = VALUES(contact_email),
  remark = VALUES(remark), status = VALUES(status), owner_user_id = VALUES(owner_user_id),
  updated_at = UTC_TIMESTAMP(6);

INSERT INTO merchant_application (
  application_id, app_id, merchant_id, name, status, created_at, updated_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000001', '-', '')),
   'app_demo_starry', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')),
   '星河收银台', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000002', '-', '')),
   'mp_app_demo_deletable', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000003', '-', '')),
   '青禾测试应用', 'DISABLED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000003', '-', '')),
   'mp_app_demo_disabled_merchant', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000002', '-', '')),
   '云帆预配置应用', 'DISABLED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000004', '-', '')),
   'mp_app_demo_transaction', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000004', '-', '')),
   '远山经营应用', 'DISABLED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  name = VALUES(name), status = VALUES(status), updated_at = UTC_TIMESTAMP(6);

INSERT INTO payment_order (
  pay_order_id, pay_order_no, merchant_id, application_id, app_id, merchant_order_no,
  payer_user_id, amount_cent, currency, subject, channel, status, expires_at,
  version, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000001', '-', '')),
  'P202608030000000001',
  UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000004', '-', '')),
  UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000004', '-', '')),
  'mp_app_demo_transaction', 'demo-merchant-order-1',
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')),
  6880, 'CNY', '演示订单', 'WALLET', 'SUCCEEDED', UTC_TIMESTAMP(6),
  0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE updated_at = UTC_TIMESTAMP(6);

-- A small, varied, repeatable local-only order set for portal demonstrations.
-- These rows do not trigger wallet postings or external callbacks.
INSERT INTO payment_order (
  pay_order_id, pay_order_no, merchant_id, application_id, app_id, merchant_order_no,
  payer_user_id, amount_cent, currency, subject, channel, status, expires_at,
  version, created_at, updated_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000011', '-', '')), 'P202608100000000011', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000001', '-', '')), 'app_demo_starry', 'DEMO-STAR-001', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 2590, 'CNY', '早餐组合', 'WALLET', 'SUCCEEDED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6) - INTERVAL 1 DAY, UTC_TIMESTAMP(6) - INTERVAL 1 DAY),
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000012', '-', '')), 'P202608100000000012', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000001', '-', '')), 'app_demo_starry', 'DEMO-STAR-002', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 4680, 'CNY', '午间套餐', 'ALIPAY', 'SUCCEEDED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6) - INTERVAL 2 DAY, UTC_TIMESTAMP(6) - INTERVAL 2 DAY),
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000013', '-', '')), 'P202608100000000013', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000001', '-', '')), 'app_demo_starry', 'DEMO-STAR-003', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 1280, 'CNY', '饮品加购', 'WECHAT', 'SUCCEEDED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6) - INTERVAL 3 DAY, UTC_TIMESTAMP(6) - INTERVAL 3 DAY),
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000014', '-', '')), 'P202608100000000014', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000001', '-', '')), 'app_demo_starry', 'DEMO-STAR-004', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 3690, 'CNY', '晚餐预订单', 'WALLET', 'PENDING', UTC_TIMESTAMP(6) + INTERVAL 15 MINUTE, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000015', '-', '')), 'P202608100000000015', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000004', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000004', '-', '')), 'mp_app_demo_transaction', 'DEMO-REST-001', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 8880, 'CNY', '双人餐', 'WALLET', 'SUCCEEDED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6) - INTERVAL 1 DAY, UTC_TIMESTAMP(6) - INTERVAL 1 DAY),
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000016', '-', '')), 'P202608100000000016', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000004', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000004', '-', '')), 'mp_app_demo_transaction', 'DEMO-REST-002', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 5280, 'CNY', '工作日午餐', 'ALIPAY', 'SUCCEEDED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6) - INTERVAL 4 DAY, UTC_TIMESTAMP(6) - INTERVAL 4 DAY),
  (UNHEX(REPLACE('019fb3d0-1200-7000-8000-000000000017', '-', '')), 'P202608100000000017', UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000004', '-', '')), UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000004', '-', '')), 'mp_app_demo_transaction', 'DEMO-REST-003', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 1680, 'CNY', '已关闭测试订单', 'WECHAT', 'CLOSED', UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, 0, UTC_TIMESTAMP(6) - INTERVAL 5 DAY, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR)
ON DUPLICATE KEY UPDATE subject = VALUES(subject), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO platform_daily_metric (
  metric_date, submitted_payment_count, successful_payment_count, payment_amount_cent,
  successful_refund_count, refund_amount_cent, calculated_at
)
WITH RECURSIVE days AS (
  SELECT 0 AS day_offset
  UNION ALL SELECT day_offset + 1 FROM days WHERE day_offset < 29
)
SELECT
  DATE(UTC_TIMESTAMP()) - INTERVAL day_offset DAY,
  120 + day_offset * 3,
  112 + day_offset * 3,
  980000 + day_offset * 18500,
  3 + MOD(day_offset, 4),
  18000 + day_offset * 700,
  UTC_TIMESTAMP(6)
FROM days
ON DUPLICATE KEY UPDATE
  submitted_payment_count = VALUES(submitted_payment_count),
  successful_payment_count = VALUES(successful_payment_count),
  payment_amount_cent = VALUES(payment_amount_cent),
  successful_refund_count = VALUES(successful_refund_count),
  refund_amount_cent = VALUES(refund_amount_cent),
  calculated_at = UTC_TIMESTAMP(6);

INSERT INTO merchant_daily_metric (
  metric_date, merchant_id, successful_payment_count, payment_amount_cent,
  successful_refund_count, refund_amount_cent, calculated_at
)
WITH RECURSIVE days AS (
  SELECT 0 AS day_offset
  UNION ALL SELECT day_offset + 1 FROM days WHERE day_offset < 29
)
SELECT
  DATE(UTC_TIMESTAMP()) - INTERVAL day_offset DAY,
  UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')),
  40 + day_offset,
  320000 + day_offset * 7000,
  1,
  5000 + day_offset * 100,
  UTC_TIMESTAMP(6)
FROM days
ON DUPLICATE KEY UPDATE
  successful_payment_count = VALUES(successful_payment_count),
  payment_amount_cent = VALUES(payment_amount_cent),
  successful_refund_count = VALUES(successful_refund_count),
  refund_amount_cent = VALUES(refund_amount_cent),
  calculated_at = UTC_TIMESTAMP(6);

INSERT INTO merchant_notification (
  notification_id, merchant_id, application_id, event_id, type, status,
  next_attempt_at, attempts, event_type, merchant_order_no, business_no,
  amount_cent, occurred_at, request_summary, response_summary, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-1400-7000-8000-000000000001', '-', '')),
  UNHEX(REPLACE('019fb3d0-1000-7000-8000-000000000001', '-', '')),
  UNHEX(REPLACE('019fb3d0-1100-7000-8000-000000000001', '-', '')),
  UNHEX(REPLACE('019fb3d0-1500-7000-8000-000000000001', '-', '')),
  'PAYMENT', 'FAILED', UTC_TIMESTAMP(6), 5, 'payment.succeeded', 'DEMO-STAR-CALLBACK-001',
  'P202608100000000011', 2590, UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
  'POST callback pending manual retry', 'MANUAL_RETRY_REQUIRED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE type = VALUES(type), status = VALUES(status), attempts = VALUES(attempts),
  event_type = VALUES(event_type), merchant_order_no = VALUES(merchant_order_no),
  business_no = VALUES(business_no), amount_cent = VALUES(amount_cent),
  occurred_at = VALUES(occurred_at), request_summary = VALUES(request_summary),
  response_summary = VALUES(response_summary), updated_at = UTC_TIMESTAMP(6);
