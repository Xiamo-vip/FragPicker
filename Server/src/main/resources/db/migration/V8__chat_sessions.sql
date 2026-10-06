CREATE TABLE chat_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(100) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_session_request (user_id, idempotency_key),
    UNIQUE KEY uk_chat_session_owner (id, user_id),
    KEY idx_chat_session_owner_updated (user_id, updated_at, id),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_session_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 100),
    CONSTRAINT chk_chat_session_times CHECK (updated_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
