package com.fragpicker.ingestion.deletion;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CleanupMapper {
    @Select("SELECT "+DeletionMapper.COLUMNS+" " + """
        FROM fragment_deletions WHERE (status<>'RUNNING' AND next_scan_at<=UTC_TIMESTAMP(3))
          OR (status='RUNNING' AND lease_expires_at<=UTC_TIMESTAMP(3))
        ORDER BY next_scan_at,fragment_id LIMIT 1 FOR UPDATE SKIP LOCKED
        """)
    DeletionRow lockNext();
    @Update("""
        UPDATE fragment_deletions SET status='RUNNING',version=version+1,attempts=attempts+1,
          lease_owner=#{token},lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(3)),error_code=NULL
        WHERE fragment_id=#{fragment} AND user_id=#{owner}
        """)
    int claim(@Param("owner") long owner,@Param("fragment") long fragment,@Param("token") String token,@Param("seconds") long seconds);
    @Select("SELECT "+DeletionMapper.COLUMNS+" FROM fragment_deletions WHERE fragment_id=#{fragmentId} AND user_id=#{userId} AND status='RUNNING' AND version=#{version} AND lease_owner=#{token} AND lease_expires_at>UTC_TIMESTAMP(3) FOR UPDATE")
    DeletionRow valid(CleanupLease lease);
    @Update("UPDATE fragment_deletions SET lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(3)) WHERE fragment_id=#{lease.fragmentId} AND user_id=#{lease.userId}")
    int renew(@Param("lease") CleanupLease lease,@Param("seconds") long seconds);
    @Update("""
        UPDATE fragment_deletions SET status=#{status},lease_owner=NULL,lease_expires_at=NULL,
          last_empty_at=IF(#{status}='CONFIRMED',UTC_TIMESTAMP(3),last_empty_at),
          next_scan_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(3)),error_code=#{error},
          attempts=IF(#{status}='FAILED',attempts,0)
        WHERE fragment_id=#{lease.fragmentId} AND user_id=#{lease.userId}
        """)
    int finish(@Param("lease") CleanupLease lease,@Param("status") String status,@Param("delay") long delay,@Param("error") String error);
}
