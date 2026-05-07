package com.example.agentx.domain.conversation.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.springframework.util.StringUtils;
import com.example.agentx.infrastructure.converter.ListConverter;
import com.example.agentx.infrastructure.entity.BaseEntity;

import java.util.ArrayList;
import java.util.List;

/** 上下文实体类，管理会话的上下文窗口 */
@TableName(value = "context", autoResultMap = true)
public class ContextEntity extends BaseEntity {

    /** 上下文唯一ID */
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话ID */
    @TableField("session_id")
    private String sessionId;

    /** 历史遗留字段，仅用于兼容迁移中的旧数据 */
    @TableField(value = "active_messages", typeHandler = ListConverter.class)
    private List<String> activeMessages;

    /** 活跃窗口起始消息ID */
    @TableField("active_window_start_message_id")
    private String activeWindowStartMessageId;

    /** 历史消息摘要 */
    @TableField("summary")
    private String summary;

    /** 无参构造函数 */
    public ContextEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<String> getActiveMessages() {
        return activeMessages == null ? new ArrayList<>() : activeMessages;
    }

    public void setActiveMessages(List<String> activeMessages) {
        this.activeMessages = activeMessages;
    }

    public String getActiveWindowStartMessageId() {
        return activeWindowStartMessageId;
    }

    public void setActiveWindowStartMessageId(String activeWindowStartMessageId) {
        this.activeWindowStartMessageId = activeWindowStartMessageId;
    }

    public boolean hasActiveWindowStartMessageId() {
        return StringUtils.hasText(activeWindowStartMessageId);
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

}
