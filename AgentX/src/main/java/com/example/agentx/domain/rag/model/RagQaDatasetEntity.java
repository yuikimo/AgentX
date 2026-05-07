package com.example.agentx.domain.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.io.Serializable;
import com.example.agentx.infrastructure.entity.BaseEntity;

/** RAG知识库数据集实体
 * @author shilong.zang
 * @date 17:27 <br/>
 */
@TableName("ai_rag_qa_dataset")
public class RagQaDatasetEntity extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = -5803685552931418952L;

    /** 数据集ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

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

    /** 迁移目标嵌入模型ID */
    private String pendingEmbeddingModelId;

    /** 迁移目标嵌入Profile ID */
    private String pendingEmbeddingProfileId;

    /** 嵌入迁移状态：READY/MIGRATING/FAILED */
    private String embeddingMigrationStatus;

    /** 嵌入迁移错误信息 */
    private String embeddingMigrationError;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getPendingEmbeddingModelId() {
        return pendingEmbeddingModelId;
    }

    public void setPendingEmbeddingModelId(String pendingEmbeddingModelId) {
        this.pendingEmbeddingModelId = pendingEmbeddingModelId;
    }

    public String getPendingEmbeddingProfileId() {
        return pendingEmbeddingProfileId;
    }

    public void setPendingEmbeddingProfileId(String pendingEmbeddingProfileId) {
        this.pendingEmbeddingProfileId = pendingEmbeddingProfileId;
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
}
