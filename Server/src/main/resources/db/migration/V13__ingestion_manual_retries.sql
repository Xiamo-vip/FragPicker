ALTER TABLE fragment_transcriptions ADD COLUMN requery_at DATETIME(3) NULL;

CREATE TABLE ingestion_retry_requests (
    user_id BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fragment_id BIGINT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    next_stage VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confirmed_new_transcription BOOLEAN NOT NULL,
    previous_task_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_task_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_submitted_at DATETIME(3) NULL,
    job_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, idempotency_key),
    KEY idx_retry_fragment (fragment_id, user_id, created_at),
    CONSTRAINT fk_retry_fragment_owner FOREIGN KEY (fragment_id, user_id) REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_retry_stage CHECK (next_stage IN ('QUEUED','MEDIA_PENDING','TRANSCRIPTION_PENDING','TRANSCRIBING','KNOWLEDGE_PENDING','INDEX_PENDING')),
    CONSTRAINT chk_retry_confirmation CHECK (confirmed_new_transcription IN (0,1)),
    CONSTRAINT chk_retry_version CHECK (job_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
