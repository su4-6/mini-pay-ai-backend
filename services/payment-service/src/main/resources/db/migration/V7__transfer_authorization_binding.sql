ALTER TABLE transfer_order
  ADD COLUMN authorization_id BINARY(16) NULL AFTER intent_id,
  ADD COLUMN authorized_at DATETIME(6) NULL AFTER authorization_id;
