package com.fragpicker.knowledge;

import com.fragpicker.ingestion.IngestionJob;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface EnrichmentMapper {
    @Select("""
            SELECT * FROM ingestion_jobs WHERE (stage = 'KNOWLEDGE_PENDING' AND next_attempt_at <= UTC_TIMESTAMP(3))
                OR (stage = 'KNOWLEDGE_ENRICHING' AND lease_expires_at <= UTC_TIMESTAMP(3))
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    IngestionJob lockNext();
    @Update("""
            UPDATE ingestion_jobs SET stage = 'KNOWLEDGE_ENRICHING', attempt_count = attempt_count + 1,
                knowledge_attempt_count = knowledge_attempt_count + 1, version = version + 1,
                lease_owner = #{owner}, lease_expires_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)), error_code = NULL WHERE id = #{id}
            """)
    int claim(@Param("id") long id, @Param("owner") String owner, @Param("seconds") long seconds);
    @Select("""
            SELECT id FROM ingestion_jobs WHERE id = #{jobId} AND user_id = #{userId} AND fragment_id = #{fragmentId}
                AND stage = 'KNOWLEDGE_ENRICHING' AND version = #{version} AND lease_owner = #{owner}
                AND lease_expires_at > UTC_TIMESTAMP(3) FOR UPDATE
            """)
    Long lockValid(EnrichmentLease lease);
    @Update("""
            UPDATE ingestion_jobs SET stage = #{stage}, error_code = #{error}, version = version + 1,
                lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = TIMESTAMPADD(SECOND, #{delay}, UTC_TIMESTAMP(3)) WHERE id = #{id}
            """)
    int finish(@Param("id") long id, @Param("stage") String stage, @Param("error") String error, @Param("delay") long delay);
    @Select("""
            SELECT m.title, m.author_name AS author, f.note, k.summary, CAST(k.keywords AS CHAR) AS keywords
            FROM fragment_knowledge k JOIN fragments f ON f.id = k.fragment_id AND f.user_id = k.user_id
            LEFT JOIN fragment_video_metadata m ON m.fragment_id = k.fragment_id AND m.user_id = k.user_id
            WHERE k.fragment_id = #{fragmentId} AND k.user_id = #{userId}
            """)
    KnowledgeSource source(EnrichmentLease lease);
    @Select("SELECT LEFT(content, 1000) FROM fragment_sentences WHERE fragment_id = #{fragmentId} AND user_id = #{userId} ORDER BY ordinal LIMIT 8")
    List<String> samples(EnrichmentLease lease);
    @Update("""
            UPDATE fragment_knowledge SET enriched_summary = #{result.summary}, bullet_points = #{points}, categories = #{categories},
                display_title = #{result.displayTitle}, introduction = #{result.introduction},
                enrichment_model = #{model}, enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = #{lease.fragmentId} AND user_id = #{lease.userId}
            """)
    int save(@Param("lease") EnrichmentLease lease, @Param("result") EnrichmentResult result, @Param("points") String points,
             @Param("categories") String categories, @Param("model") String model);
}
