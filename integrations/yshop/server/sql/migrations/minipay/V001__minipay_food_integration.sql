-- MiniPay integration schema. Apply after the base yixiang-drink-open.sql dump.
-- This file is intentionally additive and does not alter the original dump.

CREATE TABLE IF NOT EXISTS `yshop_minipay_external_identity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `provider` varchar(32) NOT NULL,
  `subject` char(36) NOT NULL,
  `member_id` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_minipay_identity_provider_subject` (`provider`, `subject`),
  UNIQUE KEY `uk_minipay_identity_member` (`provider`, `member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MiniPay external member identity';

CREATE TABLE IF NOT EXISTS `yshop_minipay_checkout_quote` (
  `quote_id` char(36) NOT NULL,
  `subject` char(36) NOT NULL,
  `member_id` bigint unsigned NOT NULL,
  `shop_id` bigint NOT NULL,
  `address_id` bigint unsigned DEFAULT NULL,
  `fulfillment_type` varchar(16) NOT NULL,
  `items_json` json NOT NULL,
  `address_snapshot_json` json DEFAULT NULL,
  `subtotal_cent` bigint NOT NULL,
  `delivery_fee_cent` bigint NOT NULL,
  `amount_cent` bigint NOT NULL,
  `currency` char(3) NOT NULL DEFAULT 'CNY',
  `expires_at` datetime(6) NOT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`quote_id`),
  KEY `idx_minipay_quote_subject_created` (`subject`, `created_at`),
  CONSTRAINT `chk_minipay_quote_amount` CHECK (`amount_cent` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Authoritative MiniPay checkout quote';

CREATE TABLE IF NOT EXISTS `yshop_minipay_payment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_ref_id` char(36) NOT NULL,
  `quote_id` char(36) NOT NULL,
  `subject` char(36) NOT NULL,
  `member_id` bigint unsigned NOT NULL,
  `yshop_order_no` varchar(32) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `amount_cent` bigint NOT NULL,
  `currency` char(3) NOT NULL DEFAULT 'CNY',
  `payment_order_id` char(36) DEFAULT NULL,
  `payment_status` varchar(16) NOT NULL DEFAULT 'UNPAID',
  `refund_status` varchar(16) NOT NULL DEFAULT 'NONE',
  `expires_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_minipay_payment_order_ref` (`order_ref_id`),
  UNIQUE KEY `uk_minipay_payment_yshop_order` (`yshop_order_no`),
  UNIQUE KEY `uk_minipay_payment_idempotency` (`subject`, `idempotency_key`),
  UNIQUE KEY `uk_minipay_payment_order_id` (`payment_order_id`),
  KEY `idx_minipay_payment_subject_created` (`subject`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='yshop order and MiniPay payment association';

CREATE TABLE IF NOT EXISTS `yshop_minipay_event_inbox` (
  `event_id` char(36) NOT NULL,
  `event_type` varchar(96) NOT NULL,
  `aggregate_key` varchar(96) NOT NULL,
  `payload_hash` char(64) NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_minipay_inbox_aggregate` (`aggregate_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Idempotent MiniPay callback inbox';

CREATE TABLE IF NOT EXISTS `yshop_minipay_refund_outbox` (
  `request_id` char(36) NOT NULL,
  `yshop_order_no` varchar(32) NOT NULL,
  `order_ref_id` char(36) NOT NULL,
  `reason` varchar(256) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`request_id`),
  UNIQUE KEY `uk_minipay_refund_order` (`yshop_order_no`),
  KEY `idx_minipay_refund_delivery` (`status`, `next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Approved MiniPay refund delivery outbox';

CREATE TABLE IF NOT EXISTS `yshop_minipay_request_nonce` (
  `nonce` varchar(96) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`nonce`),
  KEY `idx_minipay_nonce_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HMAC request replay protection';
