package com.fragpicker.knowledge.content;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentMapper {
    @Select("""
            SELECT f.id, f.source_url, f.source_host, f.note, f.business_date, f.business_zone, f.created_at, f.status, j.error_code,
                LEFT(m.title, 500) AS title, COALESCE(CHAR_LENGTH(m.title) > 500, FALSE) AS title_truncated,
                LEFT(m.author_name, 100) AS author, COALESCE(CHAR_LENGTH(m.author_name) > 100, FALSE) AS author_truncated,
                EXISTS (SELECT 1 FROM fragment_stored_media v WHERE v.fragment_id = f.id AND v.user_id = f.user_id AND v.kind = 'VIDEO') AS video,
                EXISTS (SELECT 1 FROM fragment_stored_media c WHERE c.fragment_id = f.id AND c.user_id = f.user_id AND c.kind = 'COVER') AS cover,
                (SELECT COUNT(*) FROM fragment_sentences s WHERE s.fragment_id = f.id AND s.user_id = f.user_id) AS sentence_count,
                (SELECT COUNT(*) FROM fragment_key_points p WHERE p.fragment_id = f.id AND p.user_id = f.user_id) AS key_point_count
            FROM fragments f LEFT JOIN ingestion_jobs j ON j.fragment_id = f.id AND j.user_id = f.user_id
            LEFT JOIN fragment_video_metadata m ON m.fragment_id = f.id AND m.user_id = f.user_id
            WHERE f.id = #{id} AND f.user_id = #{user}
            """)
    ContentHeader header(@Param("user") long user, @Param("id") long id);
    @Select("""
            SELECT duration_ms, LEFT(summary, 4000) AS original_summary_preview, COALESCE(CHAR_LENGTH(summary) > 4000, FALSE) AS original_summary_truncated,
                LEFT(enriched_summary, 2000) AS summary, COALESCE(CHAR_LENGTH(enriched_summary) > 2000, FALSE) AS summary_truncated,
                CAST(bullet_points AS CHAR) AS points, CAST(keywords AS CHAR) AS keywords, CAST(categories AS CHAR) AS categories, completed_at, enriched_at,
                display_title, introduction
            FROM fragment_knowledge WHERE fragment_id = #{id} AND user_id = #{user}
            """)
    ContentKnowledge knowledge(@Param("user") long user, @Param("id") long id);
}
