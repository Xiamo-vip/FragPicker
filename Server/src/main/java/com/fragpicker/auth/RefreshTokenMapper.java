package com.fragpicker.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenRecord> {
    @Select("SELECT * FROM refresh_tokens WHERE token_hash = #{hash} FOR UPDATE")
    RefreshTokenRecord lockByHash(String hash);
}
