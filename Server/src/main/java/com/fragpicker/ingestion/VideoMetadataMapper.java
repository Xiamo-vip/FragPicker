package com.fragpicker.ingestion;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VideoMetadataMapper {
    @Insert("""
            INSERT INTO fragment_video_metadata
                (fragment_id, user_id, title, video_url, cover_url, author_name, author_uid, author_avatar, parsed_at)
            VALUES (#{fragmentId}, #{userId}, #{title}, #{videoUrl}, #{coverUrl}, #{authorName},
                    #{authorUid}, #{authorAvatar}, UTC_TIMESTAMP(3))
            """)
    int insert(VideoMetadata metadata);
}
