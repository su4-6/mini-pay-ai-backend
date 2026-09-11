ALTER TABLE yshop_minipay_external_identity
  ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0 AFTER member_id,
  ADD COLUMN last_profile_synced_at DATETIME(6) NULL AFTER profile_version;

INSERT INTO system_oauth2_client (
  client_id, secret, name, logo, description, status,
  access_token_validity_seconds, refresh_token_validity_seconds,
  redirect_uris, authorized_grant_types, scopes, auto_approve_scopes,
  authorities, resource_ids, additional_information,
  creator, create_time, updater, update_time, deleted
)
SELECT
  'minipay-food-h5', 'internal-not-used', 'MiniPay 外卖 H5', '',
  'MiniPay 专用外卖免登会话', 0, 1800, 2592000,
  '[]', '["refresh_token"]', '["member.read","member.write"]',
  '["member.read","member.write"]', '[]', '[]', '{}',
  'minipay', NOW(6), 'minipay', NOW(6), b'0'
WHERE NOT EXISTS (
  SELECT 1 FROM system_oauth2_client WHERE client_id = 'minipay-food-h5' AND deleted = b'0'
);
