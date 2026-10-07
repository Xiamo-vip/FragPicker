CREATE TABLE daily_digests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
    requested_revision BIGINT NOT NULL DEFAULT 1,
    working_revision BIGINT NOT NULL DEFAULT 0,
    completed_revision BIGINT NOT NULL DEFAULT 0,
    result_json JSON NULL,
    source_count BIGINT NOT NULL DEFAULT 0,
    model_calls BIGINT NOT NULL DEFAULT 0,
    completed_source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    working_source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    working_source_count BIGINT NOT NULL DEFAULT 0,
    in_flight_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(3) NULL,
    next_run_at DATETIME(3) NOT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_manual_at DATETIME(3) NULL,
    generated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_digest_day (user_id, business_date),
    UNIQUE KEY uk_daily_digest_owner (id, user_id),
    KEY idx_daily_digest_due (status, next_run_at, id),
    KEY idx_daily_digest_expired (status, lease_expires_at, id),
    CONSTRAINT fk_daily_digest_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_daily_digest_revisions CHECK (requested_revision > 0 AND working_revision BETWEEN 0 AND requested_revision AND completed_revision BETWEEN 0 AND requested_revision),
    CONSTRAINT chk_daily_digest_counts CHECK (source_count >= 0 AND model_calls >= 0 AND working_source_count >= 0),
    CONSTRAINT chk_daily_digest_lease CHECK (
        (status = 'RUNNING' AND working_revision > 0 AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status IN ('QUEUED','READY','FAILED') AND lease_token IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT chk_daily_digest_result CHECK (
        (completed_revision = 0 AND result_json IS NULL AND generated_at IS NULL AND source_count = 0 AND model_calls = 0)
        OR (completed_revision > 0 AND result_json IS NOT NULL AND JSON_TYPE(result_json) = 'OBJECT' AND generated_at IS NOT NULL AND completed_source_hash IS NOT NULL)),
    CONSTRAINT chk_daily_digest_inflight CHECK (in_flight_hash IS NULL OR (working_revision > 0 AND status IN ('RUNNING','FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_digest_sources (
    digest_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    fragment_id BIGINT NOT NULL,
    source_json JSON NOT NULL,
    PRIMARY KEY (digest_id, revision, fragment_id),
    CONSTRAINT fk_daily_source_digest FOREIGN KEY (digest_id, user_id) REFERENCES daily_digests(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_daily_source_fragment FOREIGN KEY (fragment_id, user_id) REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_daily_source_revision CHECK (revision > 0),
    CONSTRAINT chk_daily_source_json CHECK (JSON_TYPE(source_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_digest_checkpoints (
    digest_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    piece_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (digest_id, revision, request_hash),
    KEY idx_daily_checkpoint_reuse (digest_id, request_hash, revision),
    CONSTRAINT fk_daily_checkpoint_digest FOREIGN KEY (digest_id, user_id) REFERENCES daily_digests(id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_daily_checkpoint_revision CHECK (revision > 0),
    CONSTRAINT chk_daily_checkpoint_json CHECK (JSON_TYPE(piece_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_digest_requests (
    user_id BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    digest_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_daily_request_digest FOREIGN KEY (digest_id, user_id) REFERENCES daily_digests(id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_daily_request_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
