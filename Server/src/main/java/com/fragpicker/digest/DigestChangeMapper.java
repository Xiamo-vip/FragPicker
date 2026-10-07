package com.fragpicker.digest;

import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.List;

@Mapper
public interface DigestChangeMapper {
    @Select("SELECT COUNT(*) FROM fragments WHERE user_id=#{owner} AND business_date=#{date}")
    long dayTotal(@Param("owner") long owner,@Param("date") LocalDate date);
    @Select("SELECT id FROM users WHERE id=#{owner} FOR UPDATE") Long lockOwner(long owner);
    @Select("SELECT business_date FROM fragments WHERE id=#{fragment} AND user_id=#{owner}")
    LocalDate fragmentDate(@Param("owner") long owner,@Param("fragment") long fragment);
    @Insert("""
        INSERT INTO daily_digest_changes(user_id,business_date,due_at,changed_at) VALUES (#{owner},#{date},#{due},#{now})
        ON DUPLICATE KEY UPDATE due_at=IF(version=scheduled_version,VALUES(due_at),LEAST(due_at,VALUES(due_at))),
            version=version+1,changed_at=VALUES(changed_at)
        """)
    int change(@Param("owner") long owner,@Param("date") LocalDate date,@Param("due") LocalDateTime due,@Param("now") LocalDateTime now);
    @Select("SELECT user_id,business_date,version,scheduled_version,due_at FROM daily_digest_changes WHERE version>scheduled_version AND due_at<=#{now} ORDER BY due_at,user_id,business_date LIMIT 16")
    List<DigestChange> due(LocalDateTime now);
    @Select("SELECT user_id,business_date,version,scheduled_version,due_at FROM daily_digest_changes WHERE user_id=#{owner} AND business_date=#{date} FOR UPDATE")
    DigestChange lock(@Param("owner") long owner,@Param("date") LocalDate date);
    @Update("UPDATE daily_digest_changes SET scheduled_version=version WHERE user_id=#{owner} AND business_date=#{date}")
    int acknowledge(@Param("owner") long owner,@Param("date") LocalDate date);
}
