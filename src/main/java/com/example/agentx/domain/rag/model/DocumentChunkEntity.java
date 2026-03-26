package com.example.agentx.domain.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.example.agentx.infrastructure.entity.BaseEntity;

import java.io.Serial;
import java.io.Serializable;

/**
 * 此类为文档分片实体类，映射数据库表，不进行任何操作传参使用
 */
public class DocumentChunkEntity extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 5264446804791048406L;

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String embeddingId;

    /**
     * 内容
     */
    private String text;

    /**
     * 元数据
     */
    private String metadata;

    public String getEmbeddingId() {
        return embeddingId;
    }

    public void setEmbeddingId(String embeddingId) {
        this.embeddingId = embeddingId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

}

