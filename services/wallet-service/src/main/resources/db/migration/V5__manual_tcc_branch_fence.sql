CREATE TABLE wallet_tcc_branch_fence (
  xid VARCHAR(128) NOT NULL,
  branch_id BIGINT NOT NULL,
  branch_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (xid, branch_id, branch_type),
  CONSTRAINT chk_wallet_tcc_branch_type CHECK (branch_type IN ('DEBIT', 'CREDIT')),
  CONSTRAINT chk_wallet_tcc_fence_status
    CHECK (status IN ('TRY', 'CONFIRMED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
