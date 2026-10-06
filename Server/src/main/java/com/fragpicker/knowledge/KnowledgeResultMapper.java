package com.fragpicker.knowledge;

import com.fragpicker.integration.tingwu.TingwuResult;
import org.apache.ibatis.annotations.*;

@Mapper
public interface KnowledgeResultMapper {
    @Insert("""
            INSERT INTO fragment_knowledge (fragment_id, user_id, duration_ms, summary, keywords, completed_at)
            VALUES (#{fragment}, #{user}, #{duration}, #{summary}, #{keywords}, UTC_TIMESTAMP(3))
            """)
    int insert(@Param("fragment") long fragment, @Param("user") long user, @Param("duration") long duration,
               @Param("summary") String summary, @Param("keywords") String keywords);

    @Insert("""
            INSERT INTO fragment_sentences (fragment_id, user_id, ordinal, paragraph_id, speaker_id, sentence_id, start_ms, end_ms, content)
            VALUES (#{fragment}, #{user}, #{ordinal}, #{sentence.paragraphId}, #{sentence.speakerId}, #{sentence.sentenceId}, #{sentence.startMs}, #{sentence.endMs}, #{sentence.text})
            """)
    int insertSentence(@Param("fragment") long fragment, @Param("user") long user, @Param("ordinal") int ordinal,
                       @Param("sentence") TingwuResult.Sentence sentence);

    @Insert("""
            INSERT INTO fragment_key_points (fragment_id, user_id, ordinal, sentence_id, start_ms, end_ms, content)
            VALUES (#{fragment}, #{user}, #{ordinal}, #{point.sentenceId}, #{point.startMs}, #{point.endMs}, #{point.text})
            """)
    int insertPoint(@Param("fragment") long fragment, @Param("user") long user, @Param("ordinal") int ordinal,
                    @Param("point") TingwuResult.KeyPoint point);
}
