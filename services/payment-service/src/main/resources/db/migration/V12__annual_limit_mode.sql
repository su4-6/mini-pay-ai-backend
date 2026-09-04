ALTER TABLE transfer_order
  ADD COLUMN annual_limit_mode VARCHAR(32) NULL AFTER authorization_id,
  ADD CONSTRAINT chk_transfer_annual_limit_mode
    CHECK (annual_limit_mode IS NULL OR annual_limit_mode IN ('LIMITED', 'EXEMPT_ACTIVE_CARD'));

ALTER TABLE payment_order
  ADD COLUMN annual_limit_mode VARCHAR(32) NULL AFTER authorization_id,
  ADD CONSTRAINT chk_payment_annual_limit_mode
    CHECK (annual_limit_mode IS NULL OR annual_limit_mode IN ('LIMITED', 'EXEMPT_ACTIVE_CARD'));
