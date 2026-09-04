CREATE TABLE merchant_onboarding_guard (
    user_id BINARY(16) NOT NULL,
    apply_id BIGINT NULL,
    merchant_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_merchant_onboarding_guard_apply (apply_id),
    UNIQUE KEY uk_merchant_onboarding_guard_merchant (merchant_id),
    CONSTRAINT fk_merchant_onboarding_guard_apply
        FOREIGN KEY (apply_id) REFERENCES merchant_apply (id),
    CONSTRAINT fk_merchant_onboarding_guard_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Preserve all historical applications while choosing the newest one as the
-- single Android/customer-facing application for each user.
INSERT INTO merchant_onboarding_guard (user_id, apply_id, merchant_id, created_at, updated_at)
SELECT a.user_id,
       a.id,
       a.resultant_merchant_id,
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6)
  FROM merchant_apply a
  JOIN (
        SELECT user_id, MAX(id) AS latest_id
          FROM merchant_apply
         GROUP BY user_id
       ) latest ON latest.latest_id = a.id;
