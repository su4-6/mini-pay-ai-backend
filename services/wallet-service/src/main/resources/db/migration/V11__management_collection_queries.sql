CREATE INDEX idx_wallet_bill_collection_global
  ON wallet_bill (source, status, occurred_at DESC);
