ALTER TABLE transfer_order
  DROP CHECK chk_transfer_tcc_state,
  DROP COLUMN tcc_state,
  ADD COLUMN recovery_error_code VARCHAR(64) NULL AFTER failure_code,
  ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0 AFTER recovery_error_code,
  ADD COLUMN next_recovery_at DATETIME(6) NULL AFTER recovery_attempts,
  ADD COLUMN execution_lease_until DATETIME(6) NULL AFTER next_recovery_at,
  ADD KEY idx_transfer_order_recovery (status, next_recovery_at, execution_lease_until);
