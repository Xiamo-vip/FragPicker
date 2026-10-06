package com.fragpicker.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionRecord> {
    @Select("SELECT * FROM chat_sessions WHERE user_id = #{owner} AND idempotency_key = #{key}")
    ChatSessionRecord byRequest(@Param("owner") long owner, @Param("key") String key);
}
