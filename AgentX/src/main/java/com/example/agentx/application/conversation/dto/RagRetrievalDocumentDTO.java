package com.example.agentx.application.conversation.dto;

import com.example.agentx.domain.rag.constant.ConfidenceTier;

/** RAG检索结果文档DTO - 专门用于检索结果展示 轻量级设计，只包含检索结果展示所需的关键信息 与前端期望的 RetrievedFileInfo 接口完全匹配 */
public class RagRetrievalDocumentDTO {

    /** 文件ID */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** 文档ID */
    private String documentId;

    /** 相似度分数 */
    private Double score;

    /** 页码（可选，用于详情展示） */
    private Integer page;

    /** 置信度等级 */
    private ConfidenceTier confidenceTier;

    /** 本轮回答中的短引用ID */
    private String citationId;

    public RagRetrievalDocumentDTO() {
    }

    public RagRetrievalDocumentDTO(String fileId, String fileName, String documentId, Double score) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.documentId = documentId;
        this.score = score;
    }

    public RagRetrievalDocumentDTO(String fileId, String fileName, String documentId, Double score, Integer page) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.documentId = documentId;
        this.score = score;
        this.page = page;
    }

    public RagRetrievalDocumentDTO(String fileId, String fileName, String documentId, Double score, Integer page,
            ConfidenceTier confidenceTier) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.documentId = documentId;
        this.score = score;
        this.page = page;
        this.confidenceTier = confidenceTier;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public ConfidenceTier getConfidenceTier() {
        return confidenceTier;
    }

    public void setConfidenceTier(ConfidenceTier confidenceTier) {
        this.confidenceTier = confidenceTier;
    }

    public String getCitationId() {
        return citationId;
    }

    public void setCitationId(String citationId) {
        this.citationId = citationId;
    }
}
