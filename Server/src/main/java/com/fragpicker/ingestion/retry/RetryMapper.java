package com.fragpicker.ingestion.retry;

import com.fragpicker.ingestion.IngestionJob;
import com.fragpicker.ingestion.TranscriptionRecord;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RetryMapper {
    @Select("SELECT * FROM ingestion_jobs WHERE fragment_id=#{fragment} AND user_id=#{owner} FOR UPDATE")
    IngestionJob lockJob(@Param("owner") long owner, @Param("fragment") long fragment);
    @Select("""
        SELECT EXISTS(SELECT 1 FROM fragment_knowledge WHERE fragment_id=#{fragment} AND user_id=#{owner}) AS knowledge,
          EXISTS(SELECT 1 FROM fragment_knowledge WHERE fragment_id=#{fragment} AND user_id=#{owner} AND enriched_at IS NOT NULL) AS enriched,
          EXISTS(SELECT 1 FROM fragment_video_metadata WHERE fragment_id=#{fragment} AND user_id=#{owner}) AS metadata,
          EXISTS(SELECT 1 FROM fragment_stored_media WHERE fragment_id=#{fragment} AND user_id=#{owner} AND kind='VIDEO') AS video,
          EXISTS(SELECT 1 FROM fragment_video_metadata WHERE fragment_id=#{fragment} AND user_id=#{owner} AND cover_url IS NOT NULL) AS cover_required,
          EXISTS(SELECT 1 FROM fragment_stored_media WHERE fragment_id=#{fragment} AND user_id=#{owner} AND kind='COVER') AS cover
        """)
    RetryFacts facts(@Param("owner") long owner, @Param("fragment") long fragment);
    @Select("SELECT fragment_id,request_hash,next_stage,job_version FROM ingestion_retry_requests WHERE user_id=#{owner} AND idempotency_key=#{key}")
    RetrySaved find(@Param("owner") long owner, @Param("key") String key);
    @Select("SELECT COUNT(*) FROM ingestion_retry_requests WHERE user_id=#{owner} AND fragment_id=#{fragment} AND created_at>TIMESTAMPADD(SECOND,-60,UTC_TIMESTAMP(3))")
    int coolingDown(@Param("owner") long owner, @Param("fragment") long fragment);
    @Insert("""
        INSERT INTO ingestion_retry_requests(user_id,idempotency_key,fragment_id,request_hash,next_stage,
          confirmed_new_transcription,previous_task_key,previous_task_id,previous_submitted_at,job_version,created_at)
        VALUES(#{owner},#{key},#{fragment},#{hash},#{stage},#{confirmed},#{task.taskKey},#{task.taskId},#{task.submittedAt},#{version},UTC_TIMESTAMP(3))
        """)
    int remember(@Param("owner") long owner,@Param("key") String key,@Param("fragment") long fragment,
                 @Param("hash") String hash,@Param("stage") String stage,@Param("confirmed") boolean confirmed,
                 @Param("task") TranscriptionRecord task,@Param("version") long version);
    @Update("""
        UPDATE ingestion_jobs SET stage=#{stage},version=version+1,error_code=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=UTC_TIMESTAMP(3),
          attempt_count=IF(#{stage}='QUEUED',0,attempt_count),
          media_attempt_count=IF(#{stage} IN ('QUEUED','MEDIA_PENDING'),0,media_attempt_count),
          transcription_failures=IF(#{stage} IN ('QUEUED','MEDIA_PENDING','TRANSCRIPTION_PENDING','TRANSCRIBING'),0,transcription_failures),
          knowledge_attempt_count=IF(#{stage}!='INDEX_PENDING',0,knowledge_attempt_count),index_attempt_count=0
        WHERE id=#{id} AND user_id=#{owner} AND stage='FAILED'
        """)
    int resume(@Param("id") long id,@Param("owner") long owner,@Param("stage") String stage);
    @Delete("DELETE FROM fragment_transcriptions WHERE fragment_id=#{fragment} AND user_id=#{owner}")
    int removeTask(@Param("owner") long owner,@Param("fragment") long fragment);
    @Update("UPDATE fragment_transcriptions SET requery_at=UTC_TIMESTAMP(3) WHERE fragment_id=#{fragment} AND user_id=#{owner} AND task_id IS NOT NULL")
    int extendQuery(@Param("owner") long owner,@Param("fragment") long fragment);
}
