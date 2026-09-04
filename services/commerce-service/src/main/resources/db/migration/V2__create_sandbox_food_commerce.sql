ALTER TABLE outbox_event
  ADD COLUMN lease_owner VARCHAR(64) NULL AFTER next_attempt_at,
  ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_owner,
  ADD COLUMN last_error VARCHAR(512) NULL AFTER lease_until,
  ADD KEY idx_outbox_lease (status, lease_until);

CREATE TABLE merchant (
  merchant_id BINARY(16) NOT NULL,
  merchant_no VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL,
  category_code VARCHAR(32) NOT NULL,
  minimum_order_cent BIGINT NOT NULL,
  delivery_fee_cent BIGINT NOT NULL,
  estimated_delivery_minutes INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (merchant_id),
  UNIQUE KEY uk_merchant_no (merchant_no),
  KEY idx_merchant_discovery (status, category_code, estimated_delivery_minutes),
  CONSTRAINT chk_merchant_amounts CHECK (minimum_order_cent >= 0 AND delivery_fee_cent >= 0),
  CONSTRAINT chk_merchant_eta CHECK (estimated_delivery_minutes BETWEEN 1 AND 240)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_delivery_zone (
  merchant_id BINARY(16) NOT NULL,
  zone_code VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (merchant_id, zone_code),
  CONSTRAINT fk_delivery_zone_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE menu_category (
  category_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (category_id),
  KEY idx_menu_category_merchant (merchant_id, status, sort_order),
  CONSTRAINT fk_menu_category_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE menu_item (
  item_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  category_id BINARY(16) NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  taste_tags VARCHAR(256) NULL,
  allergen_tags VARCHAR(256) NULL,
  status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (item_id),
  KEY idx_menu_item_category (category_id, status, sort_order),
  KEY idx_menu_item_merchant (merchant_id, status),
  CONSTRAINT fk_menu_item_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT fk_menu_item_category FOREIGN KEY (category_id) REFERENCES menu_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE menu_sku (
  sku_id BINARY(16) NOT NULL,
  item_id BINARY(16) NOT NULL,
  name VARCHAR(128) NOT NULL,
  price_cent BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (sku_id),
  KEY idx_menu_sku_item (item_id, status),
  CONSTRAINT fk_menu_sku_item FOREIGN KEY (item_id) REFERENCES menu_item (item_id),
  CONSTRAINT chk_menu_sku_price CHECK (price_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sku_option_group (
  option_group_id BINARY(16) NOT NULL,
  item_id BINARY(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  required_flag BOOLEAN NOT NULL,
  min_select INT NOT NULL,
  max_select INT NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (option_group_id),
  KEY idx_option_group_item (item_id, sort_order),
  CONSTRAINT fk_option_group_item FOREIGN KEY (item_id) REFERENCES menu_item (item_id),
  CONSTRAINT chk_option_group_selection CHECK (min_select >= 0 AND max_select >= min_select)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sku_option (
  option_id BINARY(16) NOT NULL,
  option_group_id BINARY(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  extra_price_cent BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (option_id),
  KEY idx_sku_option_group (option_group_id, status, sort_order),
  CONSTRAINT fk_sku_option_group FOREIGN KEY (option_group_id) REFERENCES sku_option_group (option_group_id),
  CONSTRAINT chk_sku_option_price CHECK (extra_price_cent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sku_inventory (
  sku_id BINARY(16) NOT NULL,
  available_quantity INT NOT NULL,
  reserved_quantity INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (sku_id),
  CONSTRAINT fk_inventory_sku FOREIGN KEY (sku_id) REFERENCES menu_sku (sku_id),
  CONSTRAINT chk_inventory_quantities CHECK (available_quantity >= 0 AND reserved_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE delivery_address (
  address_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  label VARCHAR(32) NOT NULL,
  masked_summary VARCHAR(160) NOT NULL,
  recipient_ciphertext VARBINARY(512) NOT NULL,
  mobile_ciphertext VARBINARY(512) NOT NULL,
  address_ciphertext VARBINARY(2048) NOT NULL,
  encryption_key_version INT NOT NULL,
  zone_code VARCHAR(64) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (address_id),
  KEY idx_address_user (user_id, status, is_default),
  UNIQUE KEY uk_address_owner (user_id, address_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE promotion_rule (
  promotion_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NULL,
  name VARCHAR(128) NOT NULL,
  threshold_cent BIGINT NOT NULL,
  discount_cent BIGINT NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (promotion_id),
  KEY idx_promotion_active (merchant_id, status, starts_at, ends_at),
  CONSTRAINT fk_promotion_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT chk_promotion_amount CHECK (threshold_cent >= 0 AND discount_cent > 0 AND discount_cent <= threshold_cent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_coupon (
  user_coupon_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  promotion_id BINARY(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  used_order_id BINARY(16) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_coupon_id),
  KEY idx_user_coupon_available (user_id, status, expires_at),
  CONSTRAINT fk_user_coupon_promotion FOREIGN KEY (promotion_id) REFERENCES promotion_rule (promotion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shopping_cart (
  cart_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (cart_id),
  UNIQUE KEY uk_cart_user_merchant (user_id, merchant_id),
  CONSTRAINT fk_cart_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cart_item (
  cart_item_id BINARY(16) NOT NULL,
  cart_id BINARY(16) NOT NULL,
  sku_id BINARY(16) NOT NULL,
  quantity INT NOT NULL,
  option_signature CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (cart_item_id),
  UNIQUE KEY uk_cart_item_variant (cart_id, sku_id, option_signature),
  CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES shopping_cart (cart_id) ON DELETE CASCADE,
  CONSTRAINT fk_cart_item_sku FOREIGN KEY (sku_id) REFERENCES menu_sku (sku_id),
  CONSTRAINT chk_cart_item_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cart_item_option (
  cart_item_id BINARY(16) NOT NULL,
  option_id BINARY(16) NOT NULL,
  option_name_snapshot VARCHAR(64) NOT NULL,
  extra_price_cent_snapshot BIGINT NOT NULL,
  PRIMARY KEY (cart_item_id, option_id),
  CONSTRAINT fk_cart_option_item FOREIGN KEY (cart_item_id) REFERENCES cart_item (cart_item_id) ON DELETE CASCADE,
  CONSTRAINT fk_cart_option_option FOREIGN KEY (option_id) REFERENCES sku_option (option_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_quote (
  quote_id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  cart_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  address_id BINARY(16) NOT NULL,
  cart_version BIGINT NOT NULL,
  item_amount_cent BIGINT NOT NULL,
  delivery_fee_cent BIGINT NOT NULL,
  discount_cent BIGINT NOT NULL,
  payable_amount_cent BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (quote_id),
  UNIQUE KEY uk_quote_user_request (user_id, request_hash),
  KEY idx_quote_expiry (status, expires_at),
  CONSTRAINT fk_quote_cart FOREIGN KEY (cart_id) REFERENCES shopping_cart (cart_id),
  CONSTRAINT fk_quote_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT fk_quote_address FOREIGN KEY (address_id) REFERENCES delivery_address (address_id),
  CONSTRAINT chk_quote_amounts CHECK (item_amount_cent >= 0 AND delivery_fee_cent >= 0 AND discount_cent >= 0 AND payable_amount_cent > 0),
  CONSTRAINT chk_quote_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_quote_item (
  quote_item_id BINARY(16) NOT NULL,
  quote_id BINARY(16) NOT NULL,
  sku_id BINARY(16) NOT NULL,
  item_name_snapshot VARCHAR(128) NOT NULL,
  sku_name_snapshot VARCHAR(128) NOT NULL,
  option_summary_snapshot VARCHAR(256) NOT NULL,
  unit_price_cent BIGINT NOT NULL,
  quantity INT NOT NULL,
  line_amount_cent BIGINT NOT NULL,
  inventory_version BIGINT NOT NULL,
  PRIMARY KEY (quote_item_id),
  KEY idx_quote_item_quote (quote_id),
  CONSTRAINT fk_quote_item_quote FOREIGN KEY (quote_id) REFERENCES checkout_quote (quote_id) ON DELETE CASCADE,
  CONSTRAINT fk_quote_item_sku FOREIGN KEY (sku_id) REFERENCES menu_sku (sku_id),
  CONSTRAINT chk_quote_item_amount CHECK (unit_price_cent > 0 AND quantity > 0 AND line_amount_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE food_order (
  order_id BINARY(16) NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  user_id BINARY(16) NOT NULL,
  merchant_id BINARY(16) NOT NULL,
  merchant_name_snapshot VARCHAR(128) NOT NULL,
  address_id BINARY(16) NOT NULL,
  address_summary_snapshot VARCHAR(160) NOT NULL,
  quote_id BINARY(16) NOT NULL,
  payment_order_id BINARY(16) NULL,
  item_amount_cent BIGINT NOT NULL,
  delivery_fee_cent BIGINT NOT NULL,
  discount_cent BIGINT NOT NULL,
  payable_amount_cent BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  payment_status VARCHAR(24) NOT NULL,
  refund_status VARCHAR(24) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_food_order_no (order_no),
  UNIQUE KEY uk_food_order_quote (quote_id),
  UNIQUE KEY uk_food_order_user_request (user_id, request_hash),
  UNIQUE KEY uk_food_order_payment (payment_order_id),
  KEY idx_food_order_user (user_id, created_at),
  KEY idx_food_order_state (status, updated_at),
  CONSTRAINT fk_food_order_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT fk_food_order_address FOREIGN KEY (address_id) REFERENCES delivery_address (address_id),
  CONSTRAINT fk_food_order_quote FOREIGN KEY (quote_id) REFERENCES checkout_quote (quote_id),
  CONSTRAINT chk_food_order_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE food_order_item (
  order_item_id BINARY(16) NOT NULL,
  order_id BINARY(16) NOT NULL,
  sku_id BINARY(16) NOT NULL,
  item_name_snapshot VARCHAR(128) NOT NULL,
  sku_name_snapshot VARCHAR(128) NOT NULL,
  option_summary_snapshot VARCHAR(256) NOT NULL,
  unit_price_cent BIGINT NOT NULL,
  quantity INT NOT NULL,
  line_amount_cent BIGINT NOT NULL,
  PRIMARY KEY (order_item_id),
  KEY idx_food_order_item_order (order_id),
  CONSTRAINT fk_food_order_item_order FOREIGN KEY (order_id) REFERENCES food_order (order_id),
  CONSTRAINT chk_food_order_item_amount CHECK (unit_price_cent > 0 AND quantity > 0 AND line_amount_cent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE food_order_status_history (
  history_id BINARY(16) NOT NULL,
  order_id BINARY(16) NOT NULL,
  from_status VARCHAR(32) NULL,
  to_status VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64) NULL,
  source VARCHAR(32) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (history_id),
  KEY idx_food_order_history (order_id, occurred_at),
  CONSTRAINT fk_food_order_history_order FOREIGN KEY (order_id) REFERENCES food_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE delivery_task (
  delivery_task_id BINARY(16) NOT NULL,
  order_id BINARY(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  estimated_delivered_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (delivery_task_id),
  UNIQUE KEY uk_delivery_task_order (order_id),
  CONSTRAINT fk_delivery_task_order FOREIGN KEY (order_id) REFERENCES food_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
