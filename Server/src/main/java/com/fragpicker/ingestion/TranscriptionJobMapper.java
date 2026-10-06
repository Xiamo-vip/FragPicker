package com.fragpicker.ingestion;

import org.apache.ibatis.annotations.*;

@Mapper
public interface TranscriptionJobMapper {
    @Select("""
            SELECT * FROM ingestion_jobs WHERE
                (stage IN ('TRANSCRIPTION_PENDING', 'TRANSCRIBING') AND next_attempt_at <= UTC_TIMESTAMP(3))
                OR (stage = 'TRANSCRIPTION_WORKING' AND lease_expires_at <= UTC_TIMESTAMP(3))
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    IngestionJob lockNext();

    @Update("""
            UPDATE ingestion_jobs SET stage = 'TRANSCRIPTION_WORKING', version = version + 1,
                attempt_count = attempt_count + 1, lease_owner = #{owner},
                lease_expires_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3))
            WHERE id = #{id}
            """)
    int claim(@Param("id") long id, @Param("owner") String owner, @Param("seconds") long seconds);

    @Select("""
            SELECT * FROM ingestion_jobs WHERE id = #{jobId} AND fragment_id = #{fragmentId} AND user_id = #{userId}
            AND stage = 'TRANSCRIPTION_WORKING' AND version = #{version} AND lease_owner = #{owner}
            AND lease_expires_at > UTC_TIMESTAMP(3) FOR UPDATE
            """)
    IngestionJob lockValid(TranscriptionLease lease);

    @Select("SELECT * FROM ingestion_jobs WHERE id = #{jobId} AND fragment_id = #{fragmentId} AND user_id = #{userId} FOR UPDATE")
    IngestionJob lockOwned(TranscriptionLease lease);

    @Update("""
            UPDATE ingestion_jobs SET stage = #{stage}, error_code = #{errorCode}, transcription_failures = #{failures},
                next_attempt_at = TIMESTAMPADD(SECOND, #{delay}, UTC_TIMESTAMP(3)),
                version = version + 1, lease_owner = NULL, lease_expires_at = NULL WHERE id = #{id}
            """)
    int finish(@Param("id") long id, @Param("stage") String stage, @Param("errorCode") String errorCode,
               @Param("failures") int failures, @Param("delay") long delay);
}
