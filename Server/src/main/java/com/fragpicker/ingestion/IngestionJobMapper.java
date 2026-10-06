package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {
    @Select("""
            SELECT * FROM ingestion_jobs
            WHERE (stage = 'QUEUED' AND next_attempt_at <= UTC_TIMESTAMP(3))
               OR (stage = 'PARSING' AND lease_expires_at <= UTC_TIMESTAMP(3))
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    IngestionJob lockNextParseJob();

    @Update("""
            UPDATE ingestion_jobs SET stage = 'PARSING', attempt_count = attempt_count + 1,
                version = version + 1, lease_owner = #{owner},
                lease_expires_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)), error_code = NULL
            WHERE id = #{id}
            """)
    int claimParse(@Param("id") long id, @Param("owner") String owner, @Param("seconds") long seconds);

    @Update("""
            UPDATE ingestion_jobs SET stage = 'FAILED', version = version + 1,
                lease_owner = NULL, lease_expires_at = NULL, error_code = 'PARSE_LEASE_EXPIRED'
            WHERE id = #{id}
            """)
    int exhaustParse(long id);

    @Update("""
            UPDATE ingestion_jobs SET stage = #{stage}, error_code = #{errorCode},
                next_attempt_at = TIMESTAMPADD(SECOND, #{delaySeconds}, UTC_TIMESTAMP(3)),
                lease_owner = NULL, lease_expires_at = NULL
            WHERE id = #{lease.jobId} AND user_id = #{lease.userId} AND fragment_id = #{lease.fragmentId}
              AND stage = 'PARSING' AND version = #{lease.version} AND lease_owner = #{lease.owner}
              AND lease_expires_at > UTC_TIMESTAMP(3)
            """)
    int finishParse(@Param("lease") ParseLease lease, @Param("stage") String stage,
                    @Param("errorCode") String errorCode, @Param("delaySeconds") long delaySeconds);
}
