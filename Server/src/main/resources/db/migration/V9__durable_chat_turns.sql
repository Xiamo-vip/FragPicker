CREATE TABLE chat_turns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question VARCHAR(2000) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer MEDIUMTEXT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_expires_at DATETIME(3) NOT NULL,
    auth_version INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    context_truncated BOOLEAN NULL,
    model_rounds INT NULL,
    tool_calls INT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_turn_request (session_id, idempotency_key),
    UNIQUE KEY uk_chat_turn_owner (id, user_id),
    KEY idx_chat_turn_session_state (session_id, user_id, state, id),
    CONSTRAINT fk_chat_turn_session FOREIGN KEY (session_id, user_id) REFERENCES chat_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_question CHECK (CHAR_LENGTH(question) BETWEEN 1 AND 2000),
    CONSTRAINT chk_chat_turn_state CHECK (
        (state = 'RUNNING' AND answer IS NULL AND error_code IS NULL AND completed_at IS NULL AND model_rounds IS NULL AND tool_calls IS NULL AND context_truncated IS NULL)
        OR (state = 'COMPLETED' AND answer IS NOT NULL AND CHAR_LENGTH(answer) BETWEEN 1 AND 24000 AND error_code IS NULL AND completed_at IS NOT NULL AND model_rounds IS NOT NULL AND model_rounds BETWEEN 1 AND 4 AND tool_calls IS NOT NULL AND tool_calls BETWEEN 0 AND 3 AND context_truncated IS NOT NULL)
        OR (state = 'FAILED' AND answer IS NULL AND error_code IS NOT NULL AND completed_at IS NOT NULL AND model_rounds IS NULL AND tool_calls IS NULL AND context_truncated IS NULL)),
    CONSTRAINT chk_chat_turn_times CHECK (lease_expires_at > created_at AND (completed_at IS NULL OR completed_at >= created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_turn_sources (
    turn_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    fragment_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    snapshot JSON NOT NULL,
    PRIMARY KEY (turn_id, fragment_id),
    UNIQUE KEY uk_chat_source_order (turn_id, ordinal),
    CONSTRAINT fk_chat_source_turn FOREIGN KEY (turn_id, user_id) REFERENCES chat_turns (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_source_fragment FOREIGN KEY (fragment_id, user_id) REFERENCES fragments (id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_source_ordinal CHECK (ordinal BETWEEN 0 AND 14),
    CONSTRAINT chk_chat_source_snapshot CHECK (JSON_TYPE(snapshot) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
