package com.example.agentx.application.rag.request;

import jakarta.validation.constraints.NotBlank;

/** 安装RAG请求
 */
public class InstallRagRequest {

    /** RAG版本ID */
    @NotBlank(message = "RAG版本ID不能为空")
    private String ragVersionId;

    public String getRagVersionId() {
        return ragVersionId;
    }

    public void setRagVersionId(String ragVersionId) {
        this.ragVersionId = ragVersionId;
    }
}