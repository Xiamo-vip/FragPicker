package com.fragpicker.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    UserAccount lockById(Long id);
}
