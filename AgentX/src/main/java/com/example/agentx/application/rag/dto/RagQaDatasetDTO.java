package com.example.agentx.application.rag.dto;

import java.time.LocalDateTime;

/** RAG知识库数据集数据传输对象
 * @author shilong.zang
 * @date 2024-12-09 */
public class RagQaDatasetDTO {

    /** 数据集ID */
    private String id;

    /** 用户RAG安装记录ID（用于调用已安装RAG相关接口） */
    private String userRagId;

    /** 数据集名称 */
    private String name;

    /** 数据集图标 */
    private String icon;

    /** 数据集说明 */
    private String description;

    /** 用户ID */
    private String userId;

    /** 当前生效的嵌入模型ID */
    private String embeddingModelId;

    /** 当前生效的嵌入Profile ID */
    private String activeEmbeddingProfileId;

    /** 迁移状态：READY/MIGRATING/FAILED */
    private String embeddingMigrationStatus;

    /** 迁移错误信息 */
    private String embeddingMigrationError;

    /** 文件数量 */
    private Long fileCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserRagId() {
        return userRagId;
    }

    public void setUserRagId(String userRagId) {
        this.userRagId = userRagId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public String getActiveEmbeddingProfileId() {
        return activeEmbeddingProfileId;
    }

    public void setActiveEmbeddingProfileId(String activeEmbeddingProfileId) {
        this.activeEmbeddingProfileId = activeEmbeddingProfileId;
    }

    public String getEmbeddingMigrationStatus() {
        return embeddingMigrationStatus;
    }

    public void setEmbeddingMigrationStatus(String embeddingMigrationStatus) {
        this.embeddingMigrationStatus = embeddingMigrationStatus;
    }

    public String getEmbeddingMigrationError() {
        return embeddingMigrationError;
    }

    public void setEmbeddingMigrationError(String embeddingMigrationError) {
        this.embeddingMigrationError = embeddingMigrationError;
    }

    public Long getFileCount() {
        return fileCount;
    }

    public void setFileCount(Long fileCount) {
        this.fileCount = fileCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
