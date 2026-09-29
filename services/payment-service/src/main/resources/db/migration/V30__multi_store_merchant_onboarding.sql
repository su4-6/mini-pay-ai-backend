-- A consumer may own multiple stores. Each onboarding row maps to one merchant,
-- while every merchant continues to settle into the owner's single wallet.
ALTER TABLE merchant_onboarding_guard
    DROP PRIMARY KEY,
    ADD COLUMN guard_id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (guard_id),
    ADD KEY idx_merchant_onboarding_guard_user (user_id, created_at DESC);
