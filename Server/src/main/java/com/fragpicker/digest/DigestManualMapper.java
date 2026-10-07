package com.fragpicker.digest;

import org.apache.ibatis.annotations.*;
import java.time.*;

@Mapper
public interface DigestManualMapper {
    @Select("SELECT d.business_date AS date,r.revision,d.status FROM daily_digest_requests r JOIN daily_digests d ON d.id=r.digest_id AND d.user_id=r.user_id WHERE r.user_id=#{owner} AND r.idempotency_key=#{key}")
    Saved find(@Param("owner") long owner,@Param("key") String key);
    @Insert("INSERT INTO daily_digest_requests(user_id,idempotency_key,digest_id,revision) VALUES (#{owner},#{key},#{digest},#{revision})")
    int remember(@Param("owner") long owner,@Param("key") String key,@Param("digest") long digest,@Param("revision") long revision);
    @Update("UPDATE daily_digests SET next_run_at=#{now},last_manual_at=#{now} WHERE id=#{id}")
    int expedite(@Param("id") long id,@Param("now") LocalDateTime now);
    @Select("SELECT COUNT(*) FROM daily_digests WHERE id=#{id} AND status='RUNNING' AND lease_expires_at<=UTC_TIMESTAMP(3)")
    int expired(long id);
    record Saved(LocalDate date,long revision,String status) { }
}
