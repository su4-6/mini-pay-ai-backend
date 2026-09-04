-- Optional merchant map coordinates.
ALTER TABLE merchant
  ADD COLUMN latitude  DECIMAL(10,7) NULL,
  ADD COLUMN longitude DECIMAL(10,7) NULL;
