-- Resume local work only. Keep parsed media, OSS objects, transcription and AI checkpoints.
UPDATE fragments f JOIN ingestion_jobs j ON j.fragment_id=f.id AND j.user_id=f.user_id
JOIN fragment_knowledge k ON k.fragment_id=f.id AND k.user_id=f.user_id
LEFT JOIN fragment_indexes i ON i.fragment_id=f.id
SET f.status='INDEX_PENDING'
WHERE j.stage='FAILED' AND j.error_code='INDEX_INTERNAL_ERROR'
AND k.enriched_at IS NOT NULL AND i.fragment_id IS NULL;

UPDATE ingestion_jobs j JOIN fragment_knowledge k ON k.fragment_id=j.fragment_id AND k.user_id=j.user_id
LEFT JOIN fragment_indexes i ON i.fragment_id=j.fragment_id
SET j.stage='INDEX_PENDING', j.error_code=NULL, j.index_attempt_count=0,
j.version=j.version+1, j.lease_owner=NULL, j.lease_expires_at=NULL, j.next_attempt_at=UTC_TIMESTAMP(3)
WHERE j.stage='FAILED' AND j.error_code='INDEX_INTERNAL_ERROR'
AND k.enriched_at IS NOT NULL AND i.fragment_id IS NULL;
