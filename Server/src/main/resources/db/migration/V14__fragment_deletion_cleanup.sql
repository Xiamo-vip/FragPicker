-- Cleanup survives deletion of the fragment/account; no cascading FK to those resources.
CREATE TABLE fragment_deletions (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    deleted_at DATETIME(3) NOT NULL,
    buckets JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    next_scan_at DATETIME(3) NOT NULL,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(3) NULL,
    last_empty_at DATETIME(3) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (fragment_id, user_id),
    KEY idx_deletion_due (next_scan_at, fragment_id),
    CONSTRAINT chk_deletion_ids CHECK (fragment_id > 0 AND user_id > 0),
    CONSTRAINT chk_deletion_buckets CHECK (JSON_TYPE(buckets) = 'ARRAY' AND JSON_LENGTH(buckets) <= 4),
    CONSTRAINT chk_deletion_counters CHECK (version >= 0 AND attempts >= 0),
    CONSTRAINT chk_deletion_state CHECK (
      (status='RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
      OR (status IN ('QUEUED','CONFIRMED','FAILED') AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT chk_deletion_confirmation CHECK (status <> 'CONFIRMED' OR last_empty_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
