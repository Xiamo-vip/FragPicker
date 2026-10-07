package com.fragpicker.digest;

import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.List;

@Mapper
public interface DigestMapper {
    String COLUMNS = "id,user_id,business_date,status,requested_revision,working_revision,completed_revision,CAST(result_json AS CHAR) AS result_json,source_count,model_calls,completed_source_hash,working_source_hash,working_source_count,in_flight_hash,error_code,generated_at,last_manual_at";
    @Select("SELECT id FROM users WHERE id=#{user} FOR UPDATE")
    Long lockUser(long user);
    @Select("SELECT " + COLUMNS + " FROM daily_digests WHERE user_id=#{user} AND business_date=#{date} FOR UPDATE")
    DigestJob lockDay(@Param("user") long user, @Param("date") LocalDate date);
    @Select("SELECT " + COLUMNS + " FROM daily_digests WHERE user_id=#{user} AND business_date=#{date}")
    DigestJob day(@Param("user") long user, @Param("date") LocalDate date);
    @Insert("INSERT INTO daily_digests(user_id,business_date,next_run_at) VALUES (#{user},#{date},#{due})")
    int insert(@Param("user") long user, @Param("date") LocalDate date, @Param("due") LocalDateTime due);
    @Update("""
        UPDATE daily_digests SET requested_revision=requested_revision+1,
            in_flight_hash=IF(status='RUNNING',in_flight_hash,NULL),
            status=IF(status='RUNNING','RUNNING','QUEUED'), next_run_at=#{due}, error_code=NULL WHERE id=#{id}
        """)
    int queueRevision(@Param("id") long id, @Param("due") LocalDateTime due);
    @Select("SELECT " + COLUMNS + " " + """
        FROM daily_digests WHERE (status='QUEUED' AND next_run_at <= UTC_TIMESTAMP(3))
            OR (status='RUNNING' AND lease_expires_at <= UTC_TIMESTAMP(3))
        ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
        """)
    DigestJob lockNext();
    @Update("""
        UPDATE daily_digests SET status='RUNNING',working_revision=#{revision},lease_token=#{token},
            lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(3)),error_code=NULL,
            working_source_hash=IF(#{resume},working_source_hash,NULL),working_source_count=IF(#{resume},working_source_count,0)
        WHERE id=#{id}
        """)
    int claim(@Param("id") long id, @Param("revision") long revision, @Param("token") String token,
              @Param("seconds") long seconds, @Param("resume") boolean resume);
    @Select("SELECT " + COLUMNS + " FROM daily_digests WHERE id=#{id} AND user_id=#{userId} AND business_date=#{date} AND status='RUNNING' AND working_revision=#{revision} AND lease_token=#{token} AND lease_expires_at > UTC_TIMESTAMP(3) FOR UPDATE")
    DigestJob lockValid(DigestLease lease);
    @Update("UPDATE daily_digests SET lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(3)) WHERE id=#{id}")
    int renew(@Param("id") long id, @Param("seconds") long seconds);
    @Update("UPDATE daily_digests SET status='FAILED',error_code=#{error},lease_token=NULL,lease_expires_at=NULL WHERE id=#{id}")
    int fail(@Param("id") long id, @Param("error") String error);
    @Select("""
        <script>SELECT f.id AS fragment_id,f.user_id,f.business_date AS date,LEFT(m.title,200) AS title,
            LEFT(m.author_name,100) AS author,k.enriched_summary AS summary,CAST(k.bullet_points AS CHAR) AS points,
            CAST(k.categories AS CHAR) AS categories,CAST(k.keywords AS CHAR) AS keywords
        FROM fragments f JOIN fragment_knowledge k ON k.fragment_id=f.id AND k.user_id=f.user_id
            LEFT JOIN fragment_video_metadata m ON m.fragment_id=f.id AND m.user_id=f.user_id
        WHERE f.user_id=#{user} AND f.business_date=#{date} AND f.status='READY' AND k.enriched_at IS NOT NULL
            <if test="before != null">AND f.id &lt; #{before}</if>
        ORDER BY f.id DESC LIMIT 32</script>
        """)
    List<DigestSourceRow> readySources(@Param("user") long user, @Param("date") LocalDate date, @Param("before") Long before);
    @Insert("INSERT INTO daily_digest_sources(digest_id,user_id,revision,fragment_id,source_json) VALUES (#{lease.id},#{lease.userId},#{lease.revision},#{fragment},#{json})")
    int source(@Param("lease") DigestLease lease, @Param("fragment") long fragment, @Param("json") String json);
    @Update("UPDATE daily_digests SET working_source_hash=#{hash},working_source_count=#{count} WHERE id=#{id}")
    int snapshot(@Param("id") long id, @Param("hash") String hash, @Param("count") long count);
    @Select("""
        <script>SELECT CAST(source_json AS CHAR) FROM daily_digest_sources WHERE digest_id=#{lease.id}
            AND user_id=#{lease.userId} AND revision=#{lease.revision}
            <if test="before != null">AND fragment_id &lt; #{before}</if>
        ORDER BY fragment_id DESC LIMIT 32</script>
        """)
    List<String> sources(@Param("lease") DigestLease lease, @Param("before") Long before);
    @Select("""
        <script>SELECT COUNT(*) FROM daily_digest_sources WHERE digest_id=#{lease.id} AND user_id=#{lease.userId}
            AND revision=#{lease.revision} AND fragment_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
        """)
    int ownedReferences(@Param("lease") DigestLease lease, @Param("ids") List<Long> ids);
    @Select("SELECT COUNT(*) FROM daily_digest_sources WHERE digest_id=#{id} AND user_id=#{userId} AND revision=#{revision}")
    long sourceCount(DigestLease lease);
    @Select("SELECT CAST(piece_json AS CHAR) FROM daily_digest_checkpoints WHERE digest_id=#{lease.id} AND user_id=#{lease.userId} AND request_hash=#{hash} ORDER BY revision DESC LIMIT 1")
    String cached(@Param("lease") DigestLease lease, @Param("hash") String hash);
    @Update("UPDATE daily_digests SET in_flight_hash=#{hash} WHERE id=#{id} AND in_flight_hash IS NULL")
    int beginCall(@Param("id") long id, @Param("hash") String hash);
    @Insert("""
        INSERT INTO daily_digest_checkpoints(digest_id,user_id,revision,request_hash,piece_json)
        VALUES (#{lease.id},#{lease.userId},#{lease.revision},#{hash},#{json})
        ON DUPLICATE KEY UPDATE piece_json=VALUES(piece_json)
        """)
    int checkpoint(@Param("lease") DigestLease lease, @Param("hash") String hash, @Param("json") String json);
    @Update("UPDATE daily_digests SET in_flight_hash=NULL WHERE id=#{id}")
    int clearFlight(long id);
    @Update("""
        UPDATE daily_digests SET status=IF(requested_revision=working_revision,'READY','QUEUED'),
            completed_revision=working_revision,result_json=#{json},source_count=working_source_count,model_calls=#{calls},
            completed_source_hash=working_source_hash,generated_at=UTC_TIMESTAMP(3),lease_token=NULL,lease_expires_at=NULL,
            error_code=NULL WHERE id=#{id}
        """)
    int complete(@Param("id") long id, @Param("json") String json, @Param("calls") long calls);
}
