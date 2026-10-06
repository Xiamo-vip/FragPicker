ALTER TABLE ingestion_jobs ADD COLUMN transcription_failures INT NOT NULL DEFAULT 0;

CREATE TABLE fragment_transcriptions (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    task_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    submitted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (fragment_id),
    UNIQUE KEY uk_transcription_task_key (task_key),
    UNIQUE KEY uk_transcription_task_id (task_id),
    CONSTRAINT fk_transcription_owner FOREIGN KEY (fragment_id, user_id)
        REFERENCES fragments(id, user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fragment_knowledge (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL,
    summary LONGTEXT NULL,
    keywords JSON NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (fragment_id),
    CONSTRAINT fk_knowledge_owner FOREIGN KEY (fragment_id, user_id)
        REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_duration CHECK (duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fragment_sentences (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    paragraph_id TEXT NOT NULL,
    speaker_id TEXT NULL,
    sentence_id BIGINT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    PRIMARY KEY (fragment_id, ordinal),
    CONSTRAINT fk_sentence_owner FOREIGN KEY (fragment_id, user_id)
        REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_sentence_time CHECK (start_ms >= 0 AND end_ms >= start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fragment_key_points (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    sentence_id BIGINT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    PRIMARY KEY (fragment_id, ordinal),
    CONSTRAINT fk_key_point_owner FOREIGN KEY (fragment_id, user_id)
        REFERENCES fragments(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_key_point_time CHECK (start_ms >= 0 AND end_ms >= start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
