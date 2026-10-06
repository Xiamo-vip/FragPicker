package com.fragpicker.ingestion;

import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaJobMapper {
    @Select("""
            SELECT * FROM ingestion_jobs
            WHERE (stage = 'MEDIA_PENDING' AND next_attempt_at <= UTC_TIMESTAMP(3))
               OR (stage = 'MEDIA_SAVING' AND lease_expires_at <= UTC_TIMESTAMP(3))
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    IngestionJob lockNext();

    @Update("""
            UPDATE ingestion_jobs SET stage = 'MEDIA_SAVING', attempt_count = attempt_count + 1,
                media_attempt_count = media_attempt_count + 1, version = version + 1, lease_owner = #{owner},
                lease_expires_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)), error_code = NULL
            WHERE id = #{id}
            """)
    int claim(@Param("id") long id, @Param("owner") String owner, @Param("seconds") long seconds);

    @Update("""
            UPDATE ingestion_jobs SET stage = 'FAILED', version = version + 1,
                lease_owner = NULL, lease_expires_at = NULL, error_code = 'MEDIA_LEASE_EXPIRED'
            WHERE id = #{id}
            """)
    int exhaust(long id);

    @Select("""
            SELECT id FROM ingestion_jobs WHERE id = #{jobId} AND user_id = #{userId} AND fragment_id = #{fragmentId}
                AND stage = 'MEDIA_SAVING' AND version = #{version} AND lease_owner = #{owner}
                AND lease_expires_at > UTC_TIMESTAMP(3) FOR UPDATE
            """)
    Long lockValid(MediaLease lease);

    @Update("""
            UPDATE ingestion_jobs SET stage = #{stage}, error_code = #{errorCode},
                next_attempt_at = TIMESTAMPADD(SECOND, #{delaySeconds}, UTC_TIMESTAMP(3)),
                lease_owner = NULL, lease_expires_at = NULL
            WHERE id = #{lease.jobId} AND user_id = #{lease.userId} AND fragment_id = #{lease.fragmentId}
                AND stage = 'MEDIA_SAVING' AND version = #{lease.version} AND lease_owner = #{lease.owner}
                AND lease_expires_at > UTC_TIMESTAMP(3)
            """)
    int finish(@Param("lease") MediaLease lease, @Param("stage") String stage,
               @Param("errorCode") String errorCode, @Param("delaySeconds") long delaySeconds);
}
