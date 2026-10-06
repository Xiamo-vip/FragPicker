package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("ingestion_jobs")
public class IngestionJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fragmentId;
    private Long userId;
    private String stage;
    private Integer attemptCount;
    private Integer mediaAttemptCount;
    private Integer transcriptionFailures;
    private Integer knowledgeAttemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Long version;
    private String errorCode;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getFragmentId() { return fragmentId; }
    public void setFragmentId(Long value) { fragmentId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { stage = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public Integer getMediaAttemptCount() { return mediaAttemptCount; }
    public void setMediaAttemptCount(Integer value) { mediaAttemptCount = value; }
    public Integer getTranscriptionFailures() { return transcriptionFailures; }
    public void setTranscriptionFailures(Integer value) { transcriptionFailures = value; }
    public Integer getKnowledgeAttemptCount() { return knowledgeAttemptCount; }
    public void setKnowledgeAttemptCount(Integer value) { knowledgeAttemptCount = value; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime value) { nextAttemptAt = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime value) { leaseExpiresAt = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
