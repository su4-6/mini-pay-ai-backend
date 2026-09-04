ALTER TABLE wallet_bill
  ADD COLUMN source VARCHAR(32) NULL AFTER business_no,
  ADD KEY idx_wallet_bill_owner_source_occurred (owner_id, source, occurred_at DESC);

ALTER TABLE account_freeze
  ADD COLUMN source VARCHAR(32) NULL AFTER business_no;

ALTER TABLE pending_credit
  ADD COLUMN source VARCHAR(32) NULL AFTER business_no;
