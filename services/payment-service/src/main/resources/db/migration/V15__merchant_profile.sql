-- Extend the operations merchant baseline with its editable profile.
ALTER TABLE merchant
  ADD COLUMN short_name VARCHAR(32) NULL AFTER name,
  ADD COLUMN contact_name VARCHAR(64) NULL AFTER short_name,
  ADD COLUMN contact_mobile VARCHAR(32) NULL AFTER contact_name,
  ADD COLUMN contact_email VARCHAR(254) NULL AFTER contact_mobile,
  ADD COLUMN remark VARCHAR(500) NULL AFTER contact_email;

UPDATE merchant
   SET short_name = LEFT(name, 32)
 WHERE short_name IS NULL;

ALTER TABLE merchant
  MODIFY COLUMN short_name VARCHAR(32) NOT NULL;

CREATE INDEX idx_merchant_no_created ON merchant (merchant_no, created_at);
