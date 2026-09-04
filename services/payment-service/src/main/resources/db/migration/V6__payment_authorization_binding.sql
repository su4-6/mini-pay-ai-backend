ALTER TABLE withdrawal_order
  ADD COLUMN authorization_id BINARY(16) NULL AFTER bank_card_id,
  ADD COLUMN authorized_at DATETIME(6) NULL AFTER authorization_id;

ALTER TABLE payment_order
  ADD COLUMN authorization_id BINARY(16) NULL AFTER payer_user_id,
  ADD COLUMN authorized_at DATETIME(6) NULL AFTER authorization_id;
