package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.MediaKind;
import org.apache.ibatis.annotations.*;

@Mapper
public interface StoredMediaMapper {
    @Select("""
            SELECT fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type
            FROM fragment_stored_media WHERE fragment_id = #{fragmentId} AND user_id = #{userId} AND kind = #{kind}
            """)
    StoredMediaRecord find(@Param("fragmentId") long fragmentId, @Param("userId") long userId, @Param("kind") MediaKind kind);

    @Insert("""
            INSERT INTO fragment_stored_media
                (fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type, stored_at)
            VALUES (#{fragmentId}, #{userId}, #{kind}, #{bucket}, #{objectKey}, #{sizeBytes}, #{sha256}, #{contentType}, UTC_TIMESTAMP(3))
            """)
    int insert(StoredMediaRecord media);
}
