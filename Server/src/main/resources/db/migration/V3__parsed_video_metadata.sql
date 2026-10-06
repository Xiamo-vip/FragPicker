CREATE TABLE fragment_video_metadata (
    fragment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title TEXT NULL,
    video_url TEXT NOT NULL,
    cover_url TEXT NULL,
    author_name TEXT NULL,
    author_uid TEXT NULL,
    author_avatar TEXT NULL,
    parsed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (fragment_id),
    CONSTRAINT fk_video_fragment_owner FOREIGN KEY (fragment_id, user_id)
        REFERENCES fragments(id, user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
