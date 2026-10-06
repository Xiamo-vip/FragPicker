package com.fragpicker.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionRecord> {
    @Select("SELECT * FROM chat_sessions WHERE user_id = #{owner} AND idempotency_key = #{key}")
    ChatSessionRecord byRequest(@Param("owner") long owner, @Param("key") String key);

    @Select("""
        <script>
        SELECT id, title, created_at, updated_at FROM chat_sessions WHERE user_id = #{owner}
        <if test="before != null">
          AND (updated_at &lt; #{before} OR (updated_at = #{before} AND id &lt; #{id}))
        </if>
        ORDER BY updated_at DESC, id DESC LIMIT #{count}
        </script>
        """)
    List<ChatSessionListRow> page(@Param("owner") long owner, @Param("before") LocalDateTime before,
            @Param("id") Long id, @Param("count") int count);
}
