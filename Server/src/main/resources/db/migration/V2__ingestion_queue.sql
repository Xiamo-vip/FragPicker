CREATE TABLE fragments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_url TEXT NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_host VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note TEXT NULL,
    business_date DATE NOT NULL,
    business_zone VARCHAR(64) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_fragment_source UNIQUE (user_id, source_hash),
    CONSTRAINT uk_fragment_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_fragment_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_fragment_user_date (user_id, business_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ingestion_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    stage VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_job_fragment UNIQUE (fragment_id),
    CONSTRAINT fk_job_fragment_owner FOREIGN KEY (fragment_id, user_id) REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    INDEX idx_job_claim (stage, next_attempt_at, lease_expires_at),
    INDEX idx_job_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE submission_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fragment_id BIGINT NOT NULL,
    duplicate BOOLEAN NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_submission_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_submission_fragment_owner FOREIGN KEY (fragment_id, user_id) REFERENCES fragments(id, user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
