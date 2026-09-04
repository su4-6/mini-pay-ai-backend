CREATE INDEX idx_food_external_order_status_created
  ON food_external_order_ref (payment_status, fulfillment_status, refund_status, created_at DESC);
