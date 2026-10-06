package com.fragpicker.history;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DailyFragmentsMapper {
    @Select("""
        <script>
        SELECT id FROM fragments WHERE user_id = #{owner} AND business_date = #{date}
        <if test="before != null">AND id &lt; #{before}</if>
        ORDER BY id DESC LIMIT #{count}
        </script>
        """)
    List<Long> page(@Param("owner") long owner, @Param("date") LocalDate date, @Param("before") Long before, @Param("count") int count);
}
