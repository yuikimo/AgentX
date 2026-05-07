package com.example.agentx.domain.conversation.model;

import com.baomidou.mybatisplus.annotation.*;
import com.example.agentx.infrastructure.converter.JsonbStringConverter;
import com.example.agentx.infrastructure.entity.BaseEntity;

import java.time.LocalDateTime;

/** 会话实体类，代表一个独立的对话会话/主题 */
@TableName(value = "sessions", autoResultMap = true)
public class SessionEntity extends BaseEntity {

    public static final int TITLE_MAX_LENGTH = 20;
    public static final int SMART_TITLE_MAX_LENGTH = 20;
    public static final String DEFAULT_TITLE = "新会话";

    /** 会话唯一ID */
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    /** 会话标题 */
    @TableField("title")
    private String title;

    /** 所属用户ID */
    @TableField("user_id")
    private String userId;

    /** 关联的Agent版本ID */
    @TableField("agent_id")
    private String agentId;

    /** 会话描述 */
    @TableField("description")
    private String description;

    /** 是否归档 */
    @TableField("is_archived")
    private boolean isArchived;

    /** 会话元数据，可存储其他自定义信息 */
    @TableField(value = "metadata", typeHandler = JsonbStringConverter.class)
    private String metadata;

    /** 标题是否已完成命名（手动或智能） */
    @TableField("title_renamed")
    private boolean titleRenamed;

    /** 无参构造函数 */
    public SessionEntity() {
    }

    // Getter和Setter方法
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = normalizeTitle(title);
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentVersionId) {
        this.agentId = agentVersionId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isArchived() {
        return isArchived;
    }

    public void setArchived(boolean archived) {
        isArchived = archived;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public boolean isTitleRenamed() {
        return titleRenamed;
    }

    public boolean getTitleRenamed() {
        return titleRenamed;
    }

    public void setTitleRenamed(boolean titleRenamed) {
        this.titleRenamed = titleRenamed;
    }

    /** 创建新会话 */
    public static SessionEntity createNew(String title, String userId) {
        SessionEntity session = new SessionEntity();
        session.setTitle(title);
        session.setUserId(userId);
        session.setArchived(false);
        session.setTitleRenamed(false);
        return session;
    }

    /** 更新会话信息 */
    public void update(String title, String description) {
        this.setTitle(title);
        this.description = description;
        // 自动填充会处理更新时间
    }

    /** 归档会话 */
    public void archive() {
        this.isArchived = true;
        // 自动填充会处理更新时间
    }

    /** 恢复已归档会话 */
    public void unarchive() {
        this.isArchived = false;
        this.updatedAt = LocalDateTime.now();
    }

    public static String normalizeTitle(String title) {
        String normalized = collapseWhitespace(title);
        if (normalized == null || normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        normalized = truncateByCodePoints(normalized, TITLE_MAX_LENGTH);
        if (normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return normalized;
    }

    public static String normalizeSmartTitle(String title) {
        String firstLine = firstNonEmptyLine(title);
        String normalized = collapseWhitespace(stripSmartTitleNoise(firstLine));
        if (normalized == null || normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        normalized = truncateByCodePoints(normalized, SMART_TITLE_MAX_LENGTH);
        return normalizeTitle(normalized);
    }

    private static String stripSmartTitleNoise(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.trim();
        normalized = normalized.replaceAll("^#+\\s*", "");
        normalized = normalized.replaceAll("^[\\-*>\\s]+", "");
        normalized = normalized.replaceAll("^[\"'`“”‘’]+|[\"'`“”‘’]+$", "");
        normalized = normalized.replaceAll("[。！？!?,，；：:]+$", "");
        return normalized;
    }

    private static String firstNonEmptyLine(String title) {
        if (title == null) {
            return null;
        }
        String[] lines = title.split("\\R");
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                return line;
            }
        }
        return title;
    }

    private static String collapseWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String truncateByCodePoints(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maxCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, endIndex).trim();
    }

}
