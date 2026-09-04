CREATE TABLE friend_relation (
  id BINARY(16) NOT NULL,
  user_id BINARY(16) NOT NULL,
  friend_id BINARY(16) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_friend_relation (user_id, friend_id),
  INDEX idx_friend_relation_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
