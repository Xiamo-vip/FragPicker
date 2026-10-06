ALTER TABLE ingestion_jobs ADD COLUMN knowledge_attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE fragment_knowledge
    ADD COLUMN enriched_summary LONGTEXT NULL,
    ADD COLUMN bullet_points JSON NULL,
    ADD COLUMN categories JSON NULL,
    ADD COLUMN enrichment_model VARCHAR(128) NULL,
    ADD COLUMN enriched_at DATETIME(3) NULL;
