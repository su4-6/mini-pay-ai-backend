-- Some local databases were upgraded through a migration ordering collision where
-- V16 was recorded but merchant_application.version was not retained. The payment
-- service uses this column for optimistic locking, so repair the drift idempotently.
SET @merchant_application_version_count = (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'merchant_application'
     AND column_name = 'version'
);
SET @restore_merchant_application_version = IF(
  @merchant_application_version_count = 0,
  'ALTER TABLE merchant_application ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1'
);
PREPARE restore_merchant_application_version_statement
  FROM @restore_merchant_application_version;
EXECUTE restore_merchant_application_version_statement;
DEALLOCATE PREPARE restore_merchant_application_version_statement;
