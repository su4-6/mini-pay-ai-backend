CREATE INDEX idx_wallet_bill_transfer_counterparty_time
  ON wallet_bill (owner_id, business_type, status, counterparty_user_id, occurred_at DESC);
