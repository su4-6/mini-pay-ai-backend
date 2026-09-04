ALTER TABLE food_external_binding
  ADD COLUMN authorization_id BINARY(16) NULL AFTER provider_member_id,
  ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0 AFTER authorization_id,
  ADD COLUMN profile_sync_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER profile_version,
  ADD COLUMN last_profile_synced_at DATETIME(6) NULL AFTER profile_sync_status,
  ADD COLUMN last_used_at DATETIME(6) NULL AFTER last_profile_synced_at;

CREATE INDEX idx_food_binding_authorization
  ON food_external_binding (authorization_id);
