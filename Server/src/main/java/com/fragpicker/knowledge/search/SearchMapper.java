package com.fragpicker.knowledge.search;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SearchMapper {
    String FROM = """
            FROM fragment_indexes i JOIN fragments f ON f.id = i.fragment_id AND f.user_id = i.user_id
            JOIN fragment_knowledge k ON k.fragment_id = i.fragment_id AND k.user_id = i.user_id
            LEFT JOIN fragment_video_metadata m ON m.fragment_id = i.fragment_id AND m.user_id = i.user_id
            """;
    String FILTER = """
            WHERE i.user_id = #{scope.userId} AND f.status = 'READY' AND i.model_id = #{model} AND i.chunker_version = #{chunker} AND i.dimensions = 512
            <if test="scope.fromDate != null">AND f.business_date &gt;= #{scope.fromDate}</if>
            <if test="scope.toDate != null">AND f.business_date &lt;= #{scope.toDate}</if>
            <if test="scope.category != null">AND JSON_CONTAINS(k.categories, JSON_QUOTE(#{scope.category})) = 1</if>
            <if test="scope.author != null">AND LOCATE(#{scope.author}, m.author_name) &gt; 0</if>
            <if test="scope.keyword != null">AND (
                LOCATE(#{scope.keyword}, m.title) &gt; 0 OR LOCATE(#{scope.keyword}, m.author_name) &gt; 0 OR LOCATE(#{scope.keyword}, f.note) &gt; 0
                OR LOCATE(#{scope.keyword}, k.summary) &gt; 0 OR LOCATE(#{scope.keyword}, k.enriched_summary) &gt; 0
                OR LOCATE(#{scope.keyword}, CAST(k.keywords AS CHAR)) &gt; 0 OR LOCATE(#{scope.keyword}, CAST(k.bullet_points AS CHAR)) &gt; 0
                OR EXISTS (SELECT 1 FROM fragment_sentences kw WHERE kw.fragment_id = i.fragment_id AND kw.user_id = i.user_id AND LOCATE(#{scope.keyword}, kw.content) &gt; 0)
                OR EXISTS (SELECT 1 FROM fragment_key_points kw WHERE kw.fragment_id = i.fragment_id AND kw.user_id = i.user_id AND LOCATE(#{scope.keyword}, kw.content) &gt; 0))</if>
            """;
    @Select("<script>SELECT COALESCE(SUM(i.chunk_count), 0) " + FROM + FILTER + "</script>")
    long countChunks(@Param("scope") SearchScope scope, @Param("model") String model, @Param("chunker") String chunker);
    @Select("""
            <script>SELECT c.fragment_id, c.ordinal, c.source_kind, c.source_ordinal, c.start_ms, c.end_ms, c.content, c.embedding
            """ + FROM + " JOIN fragment_index_chunks c ON c.fragment_id = i.fragment_id AND c.user_id = i.user_id " + FILTER + """
            AND (c.fragment_id &gt; #{afterFragment} OR (c.fragment_id = #{afterFragment} AND c.ordinal &gt; #{afterOrdinal}))
            ORDER BY c.fragment_id, c.ordinal LIMIT #{size}</script>
            """)
    List<SearchChunk> page(@Param("scope") SearchScope scope, @Param("model") String model, @Param("chunker") String chunker,
                           @Param("afterFragment") long afterFragment, @Param("afterOrdinal") int afterOrdinal, @Param("size") int size);
    @Select("""
            <script>SELECT f.id AS fragment_id, LEFT(COALESCE(k.display_title, m.title), 500) AS title, LEFT(m.author_name, 100) AS author, f.business_date,
                LEFT(COALESCE(k.introduction, k.enriched_summary, k.summary), 2000) AS summary, CAST(k.categories AS CHAR) AS categories,
                EXISTS (SELECT 1 FROM fragment_stored_media v WHERE v.fragment_id = f.id AND v.user_id = f.user_id AND v.kind = 'VIDEO') AS video,
                EXISTS (SELECT 1 FROM fragment_stored_media c WHERE c.fragment_id = f.id AND c.user_id = f.user_id AND c.kind = 'COVER') AS cover
            """ + FROM + FILTER + " AND f.id = #{id}</script>")
    SearchCard card(@Param("scope") SearchScope scope, @Param("model") String model, @Param("chunker") String chunker, @Param("id") long id);
}
