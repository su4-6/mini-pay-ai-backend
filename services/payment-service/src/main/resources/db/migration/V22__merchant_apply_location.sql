-- Merchant onboarding application map coordinates (same scale as merchant.latitude/longitude).
ALTER TABLE merchant_apply
  ADD COLUMN latitude  DECIMAL(10,7) NULL,
  ADD COLUMN longitude DECIMAL(10,7) NULL;
