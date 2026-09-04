ALTER TABLE outbox_event
  ADD COLUMN lease_owner VARCHAR(64) NULL AFTER next_attempt_at,
  ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_owner,
  ADD COLUMN last_error VARCHAR(512) NULL AFTER lease_until,
  ADD KEY idx_outbox_lease (status, lease_until);
