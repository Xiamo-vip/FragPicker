-- Changes are durable and committed with the ingestion operation, without locking digest jobs.
CREATE TABLE daily_digest_changes (
    user_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    scheduled_version BIGINT NOT NULL DEFAULT 0,
    due_at DATETIME(3) NOT NULL,
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, business_date),
    KEY idx_digest_change_due (due_at, user_id, business_date),
    CONSTRAINT fk_digest_change_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_digest_change_version CHECK (version > 0 AND scheduled_version BETWEEN 0 AND version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing dates become eligible at 22:00 Beijing; first feeds after 22:00 wait until 00:15.
INSERT INTO daily_digest_changes (user_id, business_date, due_at)
SELECT user_id, business_date,
    IF(MIN(created_at) <= TIMESTAMP(business_date, '14:00:00'),
       TIMESTAMP(business_date, '14:00:00'), TIMESTAMP(business_date, '16:15:00'))
FROM fragments GROUP BY user_id, business_date;
