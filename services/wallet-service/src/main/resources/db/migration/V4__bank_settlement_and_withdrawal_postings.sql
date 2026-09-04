INSERT IGNORE INTO wallet_account (
  account_id, account_no, owner_type, owner_id, currency, account_role,
  available_amount_cent, frozen_amount_cent, status, version, created_at, updated_at
) VALUES (
  UUID_TO_BIN('00000000-0000-7000-8000-000000000003'),
  'SYS-BANK-SETTLEMENT',
  'SYSTEM',
  UUID_TO_BIN('00000000-0000-7000-8000-000000000003'),
  'CNY',
  'BANK_SETTLEMENT',
  9000000000000000,
  0,
  'ACTIVE',
  0,
  UTC_TIMESTAMP(6),
  UTC_TIMESTAMP(6)
);

ALTER TABLE wallet_account
  ADD CONSTRAINT chk_wallet_account_role
    CHECK (account_role IN (
      'CONSUMER_WALLET',
      'SANDBOX_ISSUANCE',
      'INTERSHARD_CLEARING',
      'BANK_SETTLEMENT'
    ));
