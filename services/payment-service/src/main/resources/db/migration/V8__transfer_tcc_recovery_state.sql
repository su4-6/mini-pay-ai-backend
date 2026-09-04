ALTER TABLE transfer_order
  ADD COLUMN tcc_state VARCHAR(16) NOT NULL DEFAULT 'NEW' AFTER xid,
  ADD CONSTRAINT chk_transfer_tcc_state
    CHECK (tcc_state IN ('NEW', 'TRIED', 'CONFIRMING', 'CANCELLED', 'CONFIRMED'));
