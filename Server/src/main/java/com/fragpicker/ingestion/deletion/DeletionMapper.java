package com.fragpicker.ingestion.deletion;

import com.fragpicker.ingestion.FragmentRecord;
import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.List;

@Mapper
public interface DeletionMapper {
    String COLUMNS="fragment_id,user_id,business_date,CAST(buckets AS CHAR) AS buckets,status,version,attempts,deleted_at,next_scan_at,lease_owner,lease_expires_at";
    @Select("SELECT "+COLUMNS+" FROM fragment_deletions WHERE fragment_id=#{fragment} AND user_id=#{owner}")
    DeletionRow find(@Param("owner") long owner,@Param("fragment") long fragment);
    @Select("SELECT * FROM fragments WHERE id=#{fragment} AND user_id=#{owner} FOR UPDATE")
    FragmentRecord lockFragment(@Param("owner") long owner,@Param("fragment") long fragment);
    @Select("SELECT DISTINCT bucket FROM fragment_stored_media WHERE fragment_id=#{fragment} AND user_id=#{owner}")
    List<String> buckets(@Param("owner") long owner,@Param("fragment") long fragment);
    @Insert("""
        INSERT INTO fragment_deletions(fragment_id,user_id,business_date,deleted_at,buckets,status,next_scan_at,last_empty_at)
        VALUES(#{fragment},#{owner},#{date},UTC_TIMESTAMP(3),#{buckets},IF(#{empty},'CONFIRMED','QUEUED'),#{due},IF(#{empty},UTC_TIMESTAMP(3),NULL))
        """)
    int insert(@Param("owner") long owner,@Param("fragment") long fragment,@Param("date") LocalDate date,
        @Param("buckets") String buckets,@Param("empty") boolean empty,@Param("due") LocalDateTime due);
    @Delete("DELETE FROM daily_digest_checkpoints WHERE digest_id=#{digest} AND user_id=#{owner}")
    int clearCheckpoints(@Param("owner") long owner,@Param("digest") long digest);
    @Delete("DELETE FROM daily_digest_sources WHERE digest_id=#{digest} AND user_id=#{owner}")
    int clearSnapshots(@Param("owner") long owner,@Param("digest") long digest);
    @Update("""
        UPDATE daily_digests SET requested_revision=requested_revision+1,
          status=IF(in_flight_hash IS NULL,'QUEUED','FAILED'),error_code=IF(in_flight_hash IS NULL,NULL,'DIGEST_AI_UNCONFIRMED'),
          working_revision=IF(in_flight_hash IS NULL,0,working_revision),working_source_hash=NULL,working_source_count=0,
          completed_revision=0,result_json=NULL,source_count=0,model_calls=0,completed_source_hash=NULL,generated_at=NULL,
          lease_token=NULL,lease_expires_at=NULL,next_run_at=#{due}
        WHERE id=#{digest} AND user_id=#{owner}
        """)
    int invalidateDigest(@Param("owner") long owner,@Param("digest") long digest,@Param("due") LocalDateTime due);
    @Delete("DELETE FROM fragments WHERE id=#{fragment} AND user_id=#{owner}")
    int removeFragment(@Param("owner") long owner,@Param("fragment") long fragment);
    @Select("SELECT COUNT(*) FROM fragments WHERE user_id=#{owner} AND business_date=#{date}")
    long dayTotal(@Param("owner") long owner,@Param("date") LocalDate date);
    @Delete("DELETE FROM daily_digests WHERE id=#{digest} AND user_id=#{owner}")
    int removeEmptyDigest(@Param("owner") long owner,@Param("digest") long digest);
}
