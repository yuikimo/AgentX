package com.example.agentx.domain.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serial;
import java.io.Serializable;

import com.example.agentx.infrastructure.entity.BaseEntity;
import com.example.agentx.domain.rag.constant.ConfidenceTier;

import com.baomidou.mybatisplus.annotation.TableName;

/** @author shilong.zang
 * @date 20:24 <br/>
 */
@TableName("document_unit")
public class DocumentUnitEntity extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 7001509997040094844L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 文档ID */
    private String fileId;

    /** 页码 */
    private Integer page;

    /** 分片序号（同一文件内顺序） */
    private Integer chunkIndex;

    /** 当前页内容 */
    private String content;

    /** 是否进行向量化 */
    private Boolean isVector;

    /** ocr识别状态 */
    private Boolean isOcr;

    /** 相似度分数（非持久化字段，用于RAG搜索结果） */
    @TableField(exist = false)
    private Double similarityScore;

    /** 置信度等级（非持久化字段，用于RAG搜索结果） */
    @TableField(exist = false)
    private ConfidenceTier confidenceTier;

    public Double getSimilarityScore() {
        return similarityScore;
    }

    public void setSimilarityScore(Double similarityScore) {
        this.similarityScore = similarityScore;
    }

    public ConfidenceTier getConfidenceTier() {
        return confidenceTier;
    }

    public void setConfidenceTier(ConfidenceTier confidenceTier) {
        this.confidenceTier = confidenceTier;
    }

    public Boolean getIsOcr() {
        return isOcr;
    }

    public void setIsOcr(Boolean isOcr) {
        this.isOcr = isOcr;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsVector() {
        return isVector;
    }

    public void setIsVector(Boolean isVector) {
        this.isVector = isVector;
    }
}
