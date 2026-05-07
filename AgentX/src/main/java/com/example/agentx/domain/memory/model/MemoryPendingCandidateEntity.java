package com.example.agentx.domain.memory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.agentx.infrastructure.converter.ListStringConverter;
import com.example.agentx.infrastructure.entity.BaseEntity;

import java.util.List;

/** 待确认记忆候选（memory_pending_candidates） */
@TableName(value = "memory_pending_candidates", autoResultMap = true)
public class MemoryPendingCandidateEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("scope_agent_id")
    private String scopeAgentId;

    @TableField("source_session_id")
    private String sourceSessionId;

    @TableField("type")
    private String type;

    @TableField("text")
    private String text;

    @TableField("importance")
    private Float importance;

    @TableField(value = "tags", typeHandler = ListStringConverter.class)
    private List<String> tags;

    @TableField("dedupe_hash")
    private String dedupeHash;

    @TableField("seen_count")
    private Integer seenCount;

    @TableField("status")
    private Integer status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getScopeAgentId() {
        return scopeAgentId;
    }

    public void setScopeAgentId(String scopeAgentId) {
        this.scopeAgentId = scopeAgentId;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Float getImportance() {
        return importance;
    }

    public void setImportance(Float importance) {
        this.importance = importance;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getDedupeHash() {
        return dedupeHash;
    }

    public void setDedupeHash(String dedupeHash) {
        this.dedupeHash = dedupeHash;
    }

    public Integer getSeenCount() {
        return seenCount;
    }

    public void setSeenCount(Integer seenCount) {
        this.seenCount = seenCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
