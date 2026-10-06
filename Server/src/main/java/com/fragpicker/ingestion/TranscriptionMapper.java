package com.fragpicker.ingestion;

import org.apache.ibatis.annotations.*;

@Mapper
public interface TranscriptionMapper {
    @Select("SELECT fragment_id, user_id, task_key, task_id, submitted_at FROM fragment_transcriptions WHERE fragment_id = #{fragmentId} AND user_id = #{userId}")
    TranscriptionRecord find(@Param("fragmentId") long fragmentId, @Param("userId") long userId);

    @Insert("INSERT INTO fragment_transcriptions (fragment_id, user_id, task_key, submitted_at) VALUES (#{lease.fragmentId}, #{lease.userId}, #{taskKey}, UTC_TIMESTAMP(3))")
    int insert(@Param("lease") TranscriptionLease lease, @Param("taskKey") String taskKey);

    @Update("UPDATE fragment_transcriptions SET task_id = #{taskId} WHERE fragment_id = #{lease.fragmentId} AND user_id = #{lease.userId} AND task_key = #{taskKey} AND task_id IS NULL")
    int checkpoint(@Param("lease") TranscriptionLease lease, @Param("taskKey") String taskKey, @Param("taskId") String taskId);

    @Delete("DELETE FROM fragment_transcriptions WHERE fragment_id = #{fragmentId} AND user_id = #{userId} AND task_id IS NULL")
    int deleteRejected(TranscriptionLease lease);

    @Select("SELECT submitted_at <= TIMESTAMPADD(SECOND, -#{seconds}, UTC_TIMESTAMP(3)) FROM fragment_transcriptions WHERE fragment_id = #{lease.fragmentId} AND user_id = #{lease.userId}")
    Boolean expired(@Param("lease") TranscriptionLease lease, @Param("seconds") long seconds);
}
