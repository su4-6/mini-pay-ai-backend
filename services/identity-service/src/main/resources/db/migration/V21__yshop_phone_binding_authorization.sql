ALTER TABLE user_application_authorization
  ADD COLUMN bound_phone_hash BINARY(32) NULL AFTER consent_version,
  ADD COLUMN bound_phone_masked VARCHAR(32) NULL AFTER bound_phone_hash,
  ADD COLUMN bound_phone_ciphertext VARBINARY(96) NULL AFTER bound_phone_masked,
  ADD COLUMN bound_phone_nonce BINARY(12) NULL AFTER bound_phone_ciphertext,
  ADD COLUMN bound_phone_key_id VARCHAR(32) NULL AFTER bound_phone_nonce,
  ADD COLUMN phone_disclosure_version BIGINT NOT NULL DEFAULT 0 AFTER bound_phone_key_id;

UPDATE external_application
   SET display_name = '意向点餐',
       developer_name = 'MiniPay 与意向点餐',
       privacy_policy_url = 'https://food.minipay.local/#/pages/agreement/binding-agreement',
       terms_url = 'https://food.minipay.local/#/pages/agreement/terms',
       consent_version = 2,
       updated_at = UTC_TIMESTAMP(6)
 WHERE application_id = 'yshop-food';

UPDATE external_application_scope
   SET required_scope = TRUE,
       display_name = '当前位置',
       purpose = '查询附近可配送或自取门店'
 WHERE application_id = 'yshop-food'
   AND scope_code = 'location.current';
