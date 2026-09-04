ALTER TABLE merchant
  ADD COLUMN profile_confirmed_at DATETIME(6) NULL AFTER source;

UPDATE merchant m
JOIN (
  SELECT merchant_id, MAX(occurred_at) AS confirmed_at
  FROM merchant_operation_audit
  WHERE actor_type = 'MERCHANT_OWNER'
    AND operation = 'MERCHANT_PROFILE_UPDATED'
  GROUP BY merchant_id
) audit ON audit.merchant_id = m.merchant_id
SET m.profile_confirmed_at = audit.confirmed_at
WHERE m.source = 'OPS';
