-- Local demo identities for the merchant platform and payment end-to-end flow.
-- 13900000001 merchant password: MiniPay@123456; demo SMS code: 123456.

INSERT INTO user_profile (
  user_id, login_name, minipay_no, phone_hash, nickname, status,
  onboarding_status, onboarding_completed_at, version, created_at, updated_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
   'merchant-owner-demo', 'MPDEMOOWNER000001',
   UNHEX('a870acdbfcb389a58fc78851e107b10dca37c7d54c9148484220d8fce0a1f87c'),
   '多商户演示店主', 'ACTIVE', 'COMPLETED', UTC_TIMESTAMP(6), 0,
   UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')),
   'merchant-applicant-demo', 'MPDEMOAPPLICANT001',
   UNHEX('852e87fea00a4994dfc9e0900d3753168f2fa8276edf27b1b95f0a8effa57544'),
   '待入驻演示用户', 'ACTIVE', 'COMPLETED', UTC_TIMESTAMP(6), 0,
   UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')),
   'consumer-payer-demo', 'MPDEMOPAYER000001',
   UNHEX('6ac53b82d84e84c9660b698d31941d28ad8944f97ca881ea51dd4996836c27e3'),
   '演示付款用户', 'ACTIVE', 'COMPLETED', UTC_TIMESTAMP(6), 0,
   UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  nickname = VALUES(nickname), status = VALUES(status), updated_at = UTC_TIMESTAMP(6);

INSERT INTO user_credential (
  credential_id, user_id, credential_type, password_hash, status,
  failed_attempts, locked_until, created_at, updated_at
) VALUES (
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000122', '-', '')),
  UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
  'MERCHANT_LOGIN_PASSWORD',
  '$argon2id$v=19$m=16384,t=2,p=1$sTln2wzpXrX7UvK3zuCivQ$balPe8jmvJygQjQPgjmDBnnUm9e5FZHTcYrS2/Q10as',
  'ACTIVE', 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash), status = 'ACTIVE', failed_attempts = 0,
  locked_until = NULL, updated_at = UTC_TIMESTAMP(6);

INSERT IGNORE INTO user_role (user_id, role_code, created_at) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')), 'merchant_admin', UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')), 'merchant_applicant', UTC_TIMESTAMP(6));

INSERT IGNORE INTO real_name_verification (
  verification_id, user_id, idempotency_key, request_hash,
  legal_name_masked, legal_name_hash, id_number_masked, id_number_hash,
  provider, provider_reference, status, verified_at, created_at, updated_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000221', '-', '')),
   UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')), 'demo-real-name-owner',
   UNHEX(SHA2('demo-real-name-owner', 256)), '演示店主', UNHEX(SHA2('演示店主', 256)),
   '110***********0121', UNHEX(SHA2('110000199001010121', 256)),
   'DEMO', 'demo-owner-verified', 'VERIFIED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000222', '-', '')),
   UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')), 'demo-real-name-applicant',
   UNHEX(SHA2('demo-real-name-applicant', 256)), '演示用户', UNHEX(SHA2('演示用户甲', 256)),
   '110***********0111', UNHEX(SHA2('110000199001010111', 256)),
   'DEMO', 'demo-applicant-verified', 'VERIFIED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000223', '-', '')),
   UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')), 'demo-real-name-payer',
   UNHEX(SHA2('demo-real-name-payer', 256)), '演示付款人', UNHEX(SHA2('演示付款人', 256)),
   '110***********0131', UNHEX(SHA2('110000199001010131', 256)),
   'DEMO', 'demo-payer-verified', 'VERIFIED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- The existing identity Outbox publisher opens the corresponding personal wallets.
INSERT IGNORE INTO outbox_event (
  event_id, event_type, aggregate_type, aggregate_id, occurred_at,
  trace_id, payload_version, payload, status, attempts, next_attempt_at, created_at
) VALUES
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000211', '-', '')),
   'identity.user.opened', 'user', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000121', '-', '')),
   UTC_TIMESTAMP(6), 'demo-merchant-owner', 1,
   JSON_OBJECT('userId', '019fb3d0-0000-7000-8000-000000000121'),
   'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000212', '-', '')),
   'identity.user.opened', 'user', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000111', '-', '')),
   UTC_TIMESTAMP(6), 'demo-merchant-applicant', 1,
   JSON_OBJECT('userId', '019fb3d0-0000-7000-8000-000000000111'),
   'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000213', '-', '')),
   'identity.user.opened', 'user', UNHEX(REPLACE('019fb3d0-0000-7000-8000-000000000131', '-', '')),
   UTC_TIMESTAMP(6), 'demo-consumer-payer', 1,
   JSON_OBJECT('userId', '019fb3d0-0000-7000-8000-000000000131'),
   'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
