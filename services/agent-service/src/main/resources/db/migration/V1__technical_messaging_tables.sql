CREATE TABLE outbox_event (
  event_id BINARY(16) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BINARY(16) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  trace_id VARCHAR(64) NULL,
  payload_version INT NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id),
  KEY idx_outbox_delivery (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbox_message (
  event_id BINARY(16) NOT NULL,
  consumer_name VARCHAR(128) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6) NULL,
  PRIMARY KEY (event_id, consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
