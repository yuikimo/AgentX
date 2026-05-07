package com.example.agentx.application.rag.dto;

import jakarta.validation.constraints.NotBlank;

/** 切换知识库嵌入模型请求 */
public class SwitchDatasetEmbeddingModelRequest {

    @NotBlank(message = "embeddingModelId不能为空")
    private String embeddingModelId;

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }
}

