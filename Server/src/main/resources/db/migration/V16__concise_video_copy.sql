-- Display copy is generated in the existing enrichment request. Keep original metadata intact.
ALTER TABLE fragment_knowledge
    ADD COLUMN display_title VARCHAR(32) NULL,
    ADD COLUMN introduction VARCHAR(100) NULL;
