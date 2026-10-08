package com.fragpicker.history;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CalendarMapper {
    @Select("""
        SELECT days.date, days.total, days.ready, days.failed,
            COALESCE(d.completed_revision > 0 AND d.result_json IS NOT NULL AND d.source_count > 0 AND
                d.source_count = (SELECT COUNT(*) FROM daily_digest_sources s WHERE s.digest_id = d.id
                    AND s.user_id = #{owner} AND s.revision = d.completed_revision), FALSE) AS has_summary,
            COALESCE(d.requested_revision > d.completed_revision OR changes.version > changes.scheduled_version, FALSE) AS summary_outdated
        FROM (SELECT business_date AS date, COUNT(*) AS total,
                  SUM(status = 'READY') AS ready, SUM(status = 'FAILED') AS failed
              FROM fragments WHERE user_id = #{owner} AND business_date BETWEEN #{start} AND #{end}
              GROUP BY business_date) days
        LEFT JOIN daily_digests d ON d.user_id = #{owner} AND d.business_date = days.date
        LEFT JOIN daily_digest_changes changes ON changes.user_id = #{owner} AND changes.business_date = days.date
        ORDER BY days.date
        """)
    List<DayCount> month(@Param("owner") long owner, @Param("start") LocalDate start, @Param("end") LocalDate end);
    record DayCount(LocalDate date, long total, long ready, long failed, boolean hasSummary, boolean summaryOutdated) { }
}
