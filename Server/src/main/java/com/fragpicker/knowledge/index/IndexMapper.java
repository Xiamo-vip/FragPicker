package com.fragpicker.knowledge.index;

import com.fragpicker.ingestion.IngestionJob;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IndexMapper {
    @Select("""
            SELECT * FROM ingestion_jobs WHERE (stage = 'INDEX_PENDING' AND next_attempt_at <= UTC_TIMESTAMP(3))
                OR (stage = 'INDEXING' AND lease_expires_at <= UTC_TIMESTAMP(3))
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    IngestionJob lockNext();
    @Update("""
            UPDATE ingestion_jobs SET stage = 'INDEXING', attempt_count = attempt_count + 1, index_attempt_count = index_attempt_count + 1,
                version = version + 1, lease_owner = #{owner}, lease_expires_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)), error_code = NULL WHERE id = #{id}
            """)
    int claim(@Param("id") long id, @Param("owner") String owner, @Param("seconds") long seconds);
    @Select("""
            SELECT id FROM ingestion_jobs WHERE id = #{jobId} AND user_id = #{userId} AND fragment_id = #{fragmentId}
                AND stage = 'INDEXING' AND version = #{version} AND lease_owner = #{owner} AND lease_expires_at > UTC_TIMESTAMP(3) FOR UPDATE
            """)
    Long lockValid(IndexLease lease);
    @Update("UPDATE ingestion_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)) WHERE id = #{id}")
    int renew(@Param("id") long id, @Param("seconds") long seconds);
    @Update("""
            UPDATE ingestion_jobs SET stage = #{stage}, error_code = #{error}, version = version + 1,
                lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = TIMESTAMPADD(SECOND, #{delay}, UTC_TIMESTAMP(3)) WHERE id = #{id}
            """)
    int finish(@Param("id") long id, @Param("stage") String stage, @Param("error") String error, @Param("delay") long delay);
    @Select("""
            SELECT 'TITLE' AS kind, NULL AS source_ordinal, NULL AS start_ms, NULL AS end_ms, title AS content FROM fragment_video_metadata WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            UNION ALL SELECT 'AUTHOR', NULL, NULL, NULL, author_name FROM fragment_video_metadata WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            UNION ALL SELECT 'NOTE', NULL, NULL, NULL, note FROM fragments WHERE id = #{fragmentId} AND user_id = #{userId}
            UNION ALL SELECT 'SUMMARY', NULL, NULL, NULL, summary FROM fragment_knowledge WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            UNION ALL SELECT 'AI_SUMMARY', NULL, NULL, NULL, enriched_summary FROM fragment_knowledge WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            UNION ALL SELECT 'KEYWORDS', NULL, NULL, NULL, CASE WHEN JSON_LENGTH(keywords) > 0 THEN CAST(keywords AS CHAR) END FROM fragment_knowledge WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            UNION ALL SELECT 'POINTS', NULL, NULL, NULL, CASE WHEN JSON_LENGTH(bullet_points) > 0 THEN CAST(bullet_points AS CHAR) END FROM fragment_knowledge WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            """)
    List<IndexSource> metadata(IndexLease lease);
    @Select("SELECT 'TRANSCRIPT' AS kind, ordinal AS source_ordinal, start_ms, end_ms, content FROM fragment_sentences WHERE fragment_id = #{lease.fragmentId} AND user_id = #{lease.userId} AND ordinal > #{after} ORDER BY ordinal LIMIT 64")
    List<IndexSource> sentences(@Param("lease") IndexLease lease, @Param("after") int after);
    @Select("SELECT 'KEY_POINT' AS kind, ordinal AS source_ordinal, start_ms, end_ms, content FROM fragment_key_points WHERE fragment_id = #{lease.fragmentId} AND user_id = #{lease.userId} AND ordinal > #{after} ORDER BY ordinal LIMIT 64")
    List<IndexSource> points(@Param("lease") IndexLease lease, @Param("after") int after);
    @Select("SELECT COUNT(*) FROM fragment_knowledge WHERE fragment_id = #{fragmentId} AND user_id = #{userId} AND enriched_at IS NOT NULL")
    int hasKnowledge(IndexLease lease);
    @Delete("DELETE FROM fragment_indexes WHERE fragment_id = #{fragmentId} AND user_id = #{userId}")
    int deleteIndex(IndexLease lease);
    @Insert("""
            INSERT INTO fragment_indexes (fragment_id, user_id, model_id, chunker_version, dimensions, chunk_count, indexed_at)
            VALUES (#{lease.fragmentId}, #{lease.userId}, #{model}, #{chunker}, 512, #{count}, UTC_TIMESTAMP(3))
            """)
    int insertIndex(@Param("lease") IndexLease lease, @Param("model") String model, @Param("chunker") String chunker, @Param("count") int count);
    @Insert("""
            <script>
            INSERT INTO fragment_index_chunks (fragment_id, user_id, ordinal, source_kind, source_ordinal, start_ms, end_ms, content, embedding) VALUES
            <foreach collection="chunks" item="chunk" index="i" separator=",">
            (#{lease.fragmentId}, #{lease.userId}, #{offset} + #{i}, #{chunk.text.kind}, #{chunk.text.sourceOrdinal}, #{chunk.text.startMs}, #{chunk.text.endMs}, #{chunk.text.content}, #{chunk.embedding})
            </foreach>
            </script>
            """)
    int insertChunks(@Param("lease") IndexLease lease, @Param("offset") int offset, @Param("chunks") List<IndexedChunk> chunks);
}
