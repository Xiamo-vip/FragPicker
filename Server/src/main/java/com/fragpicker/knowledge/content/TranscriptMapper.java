package com.fragpicker.knowledge.content;

import org.apache.ibatis.annotations.*;

@Mapper
public interface TranscriptMapper {
    @Select("SELECT EXISTS (SELECT 1 FROM fragment_knowledge k WHERE k.fragment_id = f.id AND k.user_id = f.user_id) FROM fragments f WHERE f.id = #{id} AND f.user_id = #{user}")
    Boolean available(@Param("user") long user, @Param("id") long id);
    @Select("""
            SELECT ordinal, sentence_id, LEFT(speaker_id, 128) AS speaker_id, COALESCE(CHAR_LENGTH(speaker_id) > 128, FALSE) AS speaker_truncated,
                start_ms, end_ms, CHAR_LENGTH(content) AS length,
                SUBSTRING(content, CASE WHEN ordinal = #{ordinal} THEN #{offset} + 1 ELSE 1 END, 1000) AS text
            FROM fragment_sentences WHERE fragment_id = #{id} AND user_id = #{user} AND ordinal >= #{ordinal} ORDER BY ordinal LIMIT 1
            """)
    TranscriptRow sentence(@Param("user") long user, @Param("id") long id, @Param("ordinal") int ordinal, @Param("offset") int offset);
    @Select("""
            SELECT ordinal, sentence_id, NULL AS speaker_id, FALSE AS speaker_truncated, start_ms, end_ms, CHAR_LENGTH(content) AS length,
                SUBSTRING(content, CASE WHEN ordinal = #{ordinal} THEN #{offset} + 1 ELSE 1 END, 1000) AS text
            FROM fragment_key_points WHERE fragment_id = #{id} AND user_id = #{user} AND ordinal >= #{ordinal} ORDER BY ordinal LIMIT 1
            """)
    TranscriptRow point(@Param("user") long user, @Param("id") long id, @Param("ordinal") int ordinal, @Param("offset") int offset);
}
