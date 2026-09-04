DROP TABLE wallet_tcc_branch_fence;

ALTER TABLE wallet_account
  DROP CHECK chk_wallet_account_role,
  DROP CHECK chk_wallet_account_available_non_negative;

UPDATE wallet_account
SET account_no = 'SYS_TRANSFER_CLEARING',
    account_role = 'TRANSFER_CLEARING',
    available_amount_cent = 0,
    updated_at = UTC_TIMESTAMP(6)
WHERE account_id = UNHEX('00000000000070008000000000000002')
  AND account_role = 'INTERSHARD_CLEARING';

ALTER TABLE wallet_account
  ADD CONSTRAINT chk_wallet_account_role
    CHECK (account_role IN (
      'CONSUMER_WALLET',
      'SANDBOX_ISSUANCE',
      'TRANSFER_CLEARING',
      'BANK_SETTLEMENT'
    )),
  ADD CONSTRAINT chk_wallet_account_available_non_negative
    CHECK (account_role <> 'CONSUMER_WALLET' OR available_amount_cent >= 0);
