package com.example.agentx.application.rag.dto;

/** RAG会话响应DTO */
public class RagSessionDTO {

    /** 会话ID */
    private String sessionId;

    public RagSessionDTO() {
    }

    public RagSessionDTO(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
