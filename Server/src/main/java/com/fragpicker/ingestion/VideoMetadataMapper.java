package com.fragpicker.ingestion;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VideoMetadataMapper {
    @Insert("""
            INSERT INTO fragment_video_metadata
                (fragment_id, user_id, title, video_url, cover_url, author_name, author_uid, author_avatar, parsed_at)
            VALUES (#{fragmentId}, #{userId}, #{title}, #{videoUrl}, #{coverUrl}, #{authorName},
                    #{authorUid}, #{authorAvatar}, UTC_TIMESTAMP(3))
            """)
    int insert(VideoMetadata metadata);

    @Select("""
            SELECT fragment_id, user_id, title, video_url, cover_url, author_name, author_uid, author_avatar
            FROM fragment_video_metadata WHERE fragment_id = #{id} AND user_id = #{userId}
            """)
    VideoMetadata find(@Param("id") long id, @Param("userId") long userId);

    @Update("""
            UPDATE fragment_video_metadata SET title = #{title}, video_url = #{videoUrl}, cover_url = #{coverUrl},
                author_name = #{authorName}, author_uid = #{authorUid}, author_avatar = #{authorAvatar}, parsed_at = UTC_TIMESTAMP(3)
            WHERE fragment_id = #{fragmentId} AND user_id = #{userId}
            """)
    int replace(VideoMetadata metadata);
}
