package com.example.agentx.application.conversation.service.message.rag;

import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.rag.dto.RagSearchRequest;

/** RAG专用的聊天上下文 继承ChatContext，添加RAG特定的配置 */
public class RagChatContext extends ChatContext {

    /** RAG搜索请求配置 */
    private RagSearchRequest ragSearchRequest;

    /** 用户RAG ID（已安装的知识库ID，可选） */
    private String userRagId;

    /** 文件ID（可选，用于单文件检索） */
    private String fileId;

    /** RAG 实际检索查询 */
    private String retrievalQuery;

    /** 是否应用了查询改写 */
    private boolean rewrittenQuery;

    /** 查询改写是否因超时回退 */
    private boolean rewriteTimedOut;

    /** 查询改写耗时（毫秒） */
    private Long rewriteLatencyMs;

    /** 查询改写回退原因 */
    private String rewriteFallbackReason;

    public RagSearchRequest getRagSearchRequest() {
        return ragSearchRequest;
    }

    public void setRagSearchRequest(RagSearchRequest ragSearchRequest) {
        this.ragSearchRequest = ragSearchRequest;
    }

    public String getUserRagId() {
        return userRagId;
    }

    public void setUserRagId(String userRagId) {
        this.userRagId = userRagId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getRetrievalQuery() {
        return retrievalQuery;
    }

    public void setRetrievalQuery(String retrievalQuery) {
        this.retrievalQuery = retrievalQuery;
    }

    public boolean isRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(boolean rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public boolean isRewriteTimedOut() {
        return rewriteTimedOut;
    }

    public void setRewriteTimedOut(boolean rewriteTimedOut) {
        this.rewriteTimedOut = rewriteTimedOut;
    }

    public Long getRewriteLatencyMs() {
        return rewriteLatencyMs;
    }

    public void setRewriteLatencyMs(Long rewriteLatencyMs) {
        this.rewriteLatencyMs = rewriteLatencyMs;
    }

    public String getRewriteFallbackReason() {
        return rewriteFallbackReason;
    }

    public void setRewriteFallbackReason(String rewriteFallbackReason) {
        this.rewriteFallbackReason = rewriteFallbackReason;
    }

    /** 构建器模式创建RagChatContext */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private RagChatContext context = new RagChatContext();

        public Builder sessionId(String sessionId) {
            context.setSessionId(sessionId);
            return this;
        }

        public Builder userId(String userId) {
            context.setUserId(userId);
            return this;
        }

        public Builder userMessage(String userMessage) {
            context.setUserMessage(userMessage);
            return this;
        }

        public Builder ragSearchRequest(RagSearchRequest ragSearchRequest) {
            context.setRagSearchRequest(ragSearchRequest);
            return this;
        }

        public Builder userRagId(String userRagId) {
            context.setUserRagId(userRagId);
            return this;
        }

        public Builder fileId(String fileId) {
            context.setFileId(fileId);
            return this;
        }

        public Builder retrievalQuery(String retrievalQuery) {
            context.setRetrievalQuery(retrievalQuery);
            return this;
        }

        public Builder rewrittenQuery(boolean rewrittenQuery) {
            context.setRewrittenQuery(rewrittenQuery);
            return this;
        }

        public Builder agent(com.example.agentx.domain.agent.model.AgentEntity agent) {
            context.setAgent(agent);
            return this;
        }

        public Builder model(com.example.agentx.domain.llm.model.ModelEntity model) {
            context.setModel(model);
            return this;
        }

        public Builder provider(com.example.agentx.domain.llm.model.ProviderEntity provider) {
            context.setProvider(provider);
            return this;
        }

        public Builder contextEntity(com.example.agentx.domain.conversation.model.ContextEntity contextEntity) {
            context.setContextEntity(contextEntity);
            return this;
        }

        public Builder messageHistory(java.util.List<com.example.agentx.domain.conversation.model.MessageEntity> messageHistory) {
            context.setMessageHistory(messageHistory);
            return this;
        }

        public Builder instanceId(String instanceId) {
            context.setInstanceId(instanceId);
            return this;
        }

        public Builder streaming(boolean streaming) {
            context.setStreaming(streaming);
            return this;
        }

        public RagChatContext build() {
            return context;
        }
    }
}
