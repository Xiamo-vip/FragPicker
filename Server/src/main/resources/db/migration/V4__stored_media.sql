ALTER TABLE ingestion_jobs ADD COLUMN media_attempt_count INT NOT NULL DEFAULT 0;

CREATE TABLE fragment_stored_media (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stored_at DATETIME(3) NOT NULL,
    PRIMARY KEY (fragment_id, kind),
    CONSTRAINT fk_stored_media_owner FOREIGN KEY (fragment_id, user_id)
        REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_stored_media_kind CHECK (kind IN ('VIDEO', 'COVER')),
    CONSTRAINT ck_stored_media_size CHECK (size_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
