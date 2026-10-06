package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("submission_requests")
public class SubmissionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String idempotencyKey;
    private String requestHash;
    private Long fragmentId;
    private Boolean duplicate;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public Long getFragmentId() { return fragmentId; }
    public void setFragmentId(Long value) { fragmentId = value; }
    public Boolean getDuplicate() { return duplicate; }
    public void setDuplicate(Boolean value) { duplicate = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
