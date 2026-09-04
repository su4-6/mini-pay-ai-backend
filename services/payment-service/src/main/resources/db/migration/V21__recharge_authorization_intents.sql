ALTER TABLE recharge_order
  ADD COLUMN authorization_id BINARY(16) NULL AFTER bank_card_id,
  ADD COLUMN authorized_at DATETIME(6) NULL AFTER authorization_id,
  ADD COLUMN confirmation_expires_at DATETIME(6) NULL AFTER authorized_at,
  DROP CHECK chk_recharge_order_status,
  ADD CONSTRAINT chk_recharge_order_status
    CHECK (status IN ('PENDING_CONFIRMATION', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
  ADD KEY idx_recharge_order_pending_confirmation (status, confirmation_expires_at);
