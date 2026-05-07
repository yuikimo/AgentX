package com.example.agentx.application.rag.dto;

import com.example.agentx.domain.rag.constant.ConfidenceTier;

/** 文档单元响应DTO
 * 
 * @author shilong.zang */
public class DocumentUnitDTO {

    /** 主键 */
    private String id;

    /** 文件ID */
    private String fileId;

    /** 文件名 */
    private String filename;

    /** 页码 */
    private Integer page;

    /** 分片序号（同一文件内顺序） */
    private Integer chunkIndex;

    /** 内容 */
    private String content;

    /** 相似度分数 */
    private Double similarityScore;

    /** 置信度等级 */
    private ConfidenceTier confidenceTier;

    /** 是否OCR处理 */
    private Boolean isOcr;

    /** 是否向量化 */
    private Boolean isVector;

    /** 创建时间 */
    private String createdAt;

    /** 更新时间 */
    private String updatedAt;

    /** 本轮RAG回答中的短引用ID，例如D1、D2 */
    private String citationId;

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

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
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

    public Boolean getIsVector() {
        return isVector;
    }

    public void setIsVector(Boolean isVector) {
        this.isVector = isVector;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCitationId() {
        return citationId;
    }

    public void setCitationId(String citationId) {
        this.citationId = citationId;
    }

    @Override
    public String toString() {
        return content;

    }
}
