package com.fragpicker.chat.persistence;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ChatTurnMapper {
    @Select("SELECT id FROM chat_sessions WHERE id = #{session} AND user_id = #{owner} FOR UPDATE")
    Long lockSession(@Param("owner") long owner, @Param("session") long session);
    @Select("SELECT id FROM chat_sessions WHERE id = #{session} AND user_id = #{owner}")
    Long ownedSession(@Param("owner") long owner, @Param("session") long session);
    @Update("UPDATE chat_turns SET state = 'FAILED', error_code = 'CHAT_INTERRUPTED', completed_at = UTC_TIMESTAMP(3) WHERE session_id = #{session} AND user_id = #{owner} AND state = 'RUNNING' AND lease_expires_at <= UTC_TIMESTAMP(3)")
    int expire(@Param("owner") long owner, @Param("session") long session);
    @Select("SELECT * FROM chat_turns WHERE session_id = #{session} AND user_id = #{owner} AND idempotency_key = #{key}")
    StoredChatTurn byKey(@Param("owner") long owner, @Param("session") long session, @Param("key") String key);
    @Select("SELECT COUNT(*) FROM chat_turns WHERE session_id = #{session} AND user_id = #{owner} AND state = 'RUNNING'")
    int running(@Param("owner") long owner, @Param("session") long session);
    @Insert("""
            INSERT INTO chat_turns (session_id, user_id, idempotency_key, question, state, lease_token, lease_expires_at, auth_version, created_at)
            VALUES (#{session}, #{owner}, #{key}, #{question}, 'RUNNING', #{token}, TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)), #{version}, UTC_TIMESTAMP(3))
            """)
    int insert(@Param("owner") long owner, @Param("session") long session, @Param("key") String key, @Param("question") String question, @Param("token") String token, @Param("seconds") long seconds, @Param("version") int version);
    @Select("SELECT * FROM chat_turns WHERE id = #{id} AND session_id = #{session} AND user_id = #{owner}")
    StoredChatTurn get(@Param("owner") long owner, @Param("session") long session, @Param("id") long id);
    @Select("""
            <script>
            SELECT * FROM chat_turns WHERE session_id = #{session} AND user_id = #{owner}
            <if test="before != null">AND id &lt; #{before}</if>
            ORDER BY id DESC LIMIT #{count}
            </script>
            """)
    List<StoredChatTurn> page(@Param("owner") long owner, @Param("session") long session,
            @Param("before") Long before, @Param("count") int count);
    @Select("SELECT * FROM chat_turns WHERE id = #{id} AND session_id = #{session} AND user_id = #{owner} AND lease_token = #{token} AND state = 'RUNNING' AND lease_expires_at > UTC_TIMESTAMP(3) FOR UPDATE")
    StoredChatTurn valid(@Param("owner") long owner, @Param("session") long session, @Param("id") long id, @Param("token") String token);
    @Select("SELECT id FROM fragments WHERE id = #{id} AND user_id = #{owner} AND status = 'READY' FOR SHARE")
    Long source(@Param("owner") long owner, @Param("id") long id);
    @Insert("INSERT INTO chat_turn_sources (turn_id, user_id, fragment_id, ordinal, snapshot) VALUES (#{turn}, #{owner}, #{fragment}, #{ordinal}, #{snapshot})")
    int sourceSnapshot(@Param("owner") long owner, @Param("turn") long turn, @Param("fragment") long fragment, @Param("ordinal") int ordinal, @Param("snapshot") String snapshot);
    @Update("""
            UPDATE chat_turns SET state = 'COMPLETED', answer = #{answer}, completed_at = UTC_TIMESTAMP(3), context_truncated = #{truncated}, model_rounds = #{rounds}, tool_calls = #{calls} WHERE id = #{id}
            """)
    int complete(@Param("id") long id, @Param("answer") String answer, @Param("truncated") boolean truncated, @Param("rounds") int rounds, @Param("calls") int calls);
    @Update("UPDATE chat_turns SET state = 'FAILED', error_code = #{code}, completed_at = UTC_TIMESTAMP(3) WHERE id = #{id} AND session_id = #{session} AND user_id = #{owner} AND lease_token = #{token} AND state = 'RUNNING'")
    int fail(@Param("owner") long owner, @Param("session") long session, @Param("id") long id, @Param("token") String token, @Param("code") String code);
    @Update("UPDATE chat_sessions SET updated_at = GREATEST(updated_at, UTC_TIMESTAMP(3)) WHERE id = #{session} AND user_id = #{owner}")
    int touch(@Param("owner") long owner, @Param("session") long session);
    @Select("SELECT * FROM chat_turns WHERE session_id = #{session} AND user_id = #{owner} AND state = 'COMPLETED' AND id < #{before} ORDER BY id DESC LIMIT 9")
    List<StoredChatTurn> context(@Param("owner") long owner, @Param("session") long session, @Param("before") long before);
    @Select("SELECT fragment_id, CAST(snapshot AS CHAR) AS snapshot FROM chat_turn_sources WHERE turn_id = #{turn} AND user_id = #{owner} ORDER BY ordinal")
    List<SourceSnapshot> snapshots(@Param("owner") long owner, @Param("turn") long turn);
    record SourceSnapshot(long fragmentId, String snapshot) { @Override public String toString() { return "SourceSnapshot[content=REDACTED]"; } }
}
