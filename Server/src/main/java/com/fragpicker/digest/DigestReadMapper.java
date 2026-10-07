package com.fragpicker.digest;

import org.apache.ibatis.annotations.*;
import java.time.*;

@Mapper
public interface DigestReadMapper {
    @Select("""
        SELECT COUNT(*) AS total,COALESCE(SUM(status='READY'),0) AS ready,
            COALESCE(SUM(status NOT IN ('READY','FAILED','CANCELLED')),0) AS processing,
            COALESCE(SUM(status IN ('FAILED','CANCELLED')),0) AS failed
        FROM fragments WHERE user_id=#{owner} AND business_date=#{date}
        """)
    Counts counts(@Param("owner") long owner,@Param("date") LocalDate date);
    @Select("SELECT user_id,business_date,version,scheduled_version,due_at FROM daily_digest_changes WHERE user_id=#{owner} AND business_date=#{date}")
    DigestChange change(@Param("owner") long owner,@Param("date") LocalDate date);
    @Select("SELECT next_run_at FROM daily_digests WHERE id=#{id} AND user_id=#{userId}")
    LocalDateTime nextRun(DigestJob job);
    @Select("SELECT COUNT(*) FROM daily_digest_sources WHERE digest_id=#{id} AND user_id=#{userId} AND revision=#{completedRevision}")
    long completedSources(DigestJob job);
    @Select("""
        <script>SELECT COUNT(*) FROM daily_digest_sources WHERE digest_id=#{job.id} AND user_id=#{job.userId}
            AND revision=#{job.completedRevision} AND fragment_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
        """)
    long references(@Param("job") DigestJob job,@Param("ids") java.util.List<Long> ids);
    record Counts(long total,long ready,long processing,long failed) { }
}
