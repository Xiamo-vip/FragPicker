package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalDate;

@TableName("fragments")
public class FragmentRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sourceUrl;
    private String sourceHash;
    private String sourceHost;
    private String note;
    private LocalDate businessDate;
    private String businessZone;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String value) { sourceUrl = value; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String value) { sourceHash = value; }
    public String getSourceHost() { return sourceHost; }
    public void setSourceHost(String value) { sourceHost = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate value) { businessDate = value; }
    public String getBusinessZone() { return businessZone; }
    public void setBusinessZone(String value) { businessZone = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
