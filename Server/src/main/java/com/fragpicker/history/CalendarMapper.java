package com.fragpicker.history;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CalendarMapper {
    @Select("""
        SELECT business_date AS date, COUNT(*) AS total,
               SUM(status = 'READY') AS ready, SUM(status = 'FAILED') AS failed
        FROM fragments WHERE user_id = #{owner} AND business_date BETWEEN #{start} AND #{end}
        GROUP BY business_date ORDER BY business_date
        """)
    List<DayCount> month(@Param("owner") long owner, @Param("start") LocalDate start, @Param("end") LocalDate end);
    record DayCount(LocalDate date, long total, long ready, long failed) { }
}
