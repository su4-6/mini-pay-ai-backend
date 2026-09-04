ALTER TABLE refund_order
  ADD COLUMN failure_code VARCHAR(64) NULL AFTER status;
