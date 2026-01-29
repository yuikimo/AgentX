package com.example.agentx.domain.llm.model;

import java.util.List;

/**
 * LLM请求模型
 * 领域层定义的请求模型，不依赖具体实现
 */
public class LLMRequest {
    private final List<LLMMessage> messages;
    private final LLMRequestParameters parameters;

    public LLMRequest(List<LLMMessage> messages, LLMRequestParameters parameters) {
        this.messages = messages;
        this.parameters = parameters;
    }

    public List<LLMMessage> getMessages() {
        return messages;
    }

    public LLMRequestParameters getParameters() {
        return parameters;
    }

    /**
     * 消息类型
     */
    public enum MessageType {
        USER,
        SYSTEM,
        ASSISTANT
    }

    /**
     * LLM消息模型
     */
    public record LLMMessage(MessageType type, String content) {
    }

    /**
     * LLM请求参数
     */
    public record LLMRequestParameters(String modelId, Double temperature, Double topP) {
    }

    /**
     * 构建器
     */
    public static class Builder {
        private List<LLMMessage> messages;
        private LLMRequestParameters parameters;

        public Builder messages(List<LLMMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder parameters(LLMRequestParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        public LLMRequest build() {
            return new LLMRequest(messages, parameters);
        }
    }
}
