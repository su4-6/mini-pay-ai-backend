ALTER TABLE friend_relation
  ADD COLUMN deleted_at DATETIME(6) NULL AFTER created_at,
  ADD INDEX idx_friend_relation_active (user_id, deleted_at);
