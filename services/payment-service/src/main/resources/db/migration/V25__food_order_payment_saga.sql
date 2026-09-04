CREATE TABLE food_order_payment_reference (
  food_order_id BINARY(16) NOT NULL,
  food_order_no VARCHAR(32) NOT NULL,
  pay_order_id BINARY(16) NOT NULL,
  payer_user_id BINARY(16) NOT NULL,
  amount_cent BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  created_event_id BINARY(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (food_order_id),
  UNIQUE KEY uk_food_payment_order (pay_order_id),
  UNIQUE KEY uk_food_payment_event (created_event_id),
  KEY idx_food_payment_user (payer_user_id, created_at),
  CONSTRAINT fk_food_payment_order FOREIGN KEY (pay_order_id) REFERENCES payment_order (pay_order_id),
  CONSTRAINT chk_food_payment_amount CHECK (amount_cent > 0),
  CONSTRAINT chk_food_payment_currency CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
