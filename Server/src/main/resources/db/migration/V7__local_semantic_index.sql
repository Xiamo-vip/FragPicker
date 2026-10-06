ALTER TABLE ingestion_jobs ADD COLUMN index_attempt_count INT NOT NULL DEFAULT 0;

CREATE TABLE fragment_indexes (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    model_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunker_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dimensions INT NOT NULL,
    chunk_count INT NOT NULL,
    indexed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (fragment_id),
    UNIQUE KEY uk_index_owner (fragment_id, user_id),
    KEY ix_index_user_model (user_id, model_id, fragment_id),
    CONSTRAINT fk_index_owner FOREIGN KEY (fragment_id, user_id) REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_index_dimensions CHECK (dimensions = 512),
    CONSTRAINT ck_index_count CHECK (chunk_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fragment_index_chunks (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_ordinal INT NULL,
    start_ms BIGINT NULL,
    end_ms BIGINT NULL,
    content VARCHAR(384) NOT NULL,
    embedding VARBINARY(2048) NOT NULL,
    PRIMARY KEY (fragment_id, ordinal),
    KEY ix_chunk_user (user_id, fragment_id, ordinal),
    CONSTRAINT fk_chunk_index_owner FOREIGN KEY (fragment_id, user_id) REFERENCES fragment_indexes(fragment_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_chunk_ordinal CHECK (ordinal >= 0 AND (source_ordinal IS NULL OR source_ordinal >= 0)),
    CONSTRAINT ck_chunk_kind CHECK (source_kind IN ('TITLE', 'AUTHOR', 'NOTE', 'SUMMARY', 'AI_SUMMARY', 'KEYWORDS', 'POINTS', 'TRANSCRIPT', 'KEY_POINT')),
    CONSTRAINT ck_chunk_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 384),
    CONSTRAINT ck_chunk_vector CHECK (OCTET_LENGTH(embedding) = 2048),
    CONSTRAINT ck_chunk_time CHECK ((start_ms IS NULL AND end_ms IS NULL) OR (start_ms IS NOT NULL AND end_ms IS NOT NULL AND start_ms >= 0 AND end_ms >= start_ms))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
