ALTER TABLE food_external_binding
  ADD COLUMN provider_username VARCHAR(128) NULL AFTER provider_member_id;
