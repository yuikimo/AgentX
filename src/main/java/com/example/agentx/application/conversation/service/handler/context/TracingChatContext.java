package com.example.agentx.application.conversation.service.handler.context;

import com.example.agentx.domain.trace.model.TraceContext;

/** 支持链路追踪的ChatContext扩展 */
public class TracingChatContext extends ChatContext {

    /** 追踪上下文 */
    private TraceContext traceContext;

    public TracingChatContext() {
        super();
    }

    /** 从普通ChatContext创建TracingChatContext */
    public static TracingChatContext from(ChatContext chatContext) {
        TracingChatContext tracingContext = new TracingChatContext();

        // 复制所有属性
        tracingContext.setSessionId(chatContext.getSessionId());
        tracingContext.setUserId(chatContext.getUserId());
        tracingContext.setUserMessage(chatContext.getUserMessage());
        tracingContext.setCurrentAssistantReply(chatContext.getCurrentAssistantReply());
        tracingContext.setAgent(chatContext.getAgent());
        tracingContext.setModel(chatContext.getModel());
        tracingContext.setProvider(chatContext.getProvider());
        tracingContext.setLlmModelConfig(chatContext.getLlmModelConfig());
        tracingContext.setContextEntity(chatContext.getContextEntity());
        tracingContext.setMessageHistory(chatContext.getMessageHistory());
        tracingContext.setMcpServerNames(chatContext.getMcpServerNames());
        tracingContext.setFileUrls(chatContext.getFileUrls());
        tracingContext.setInstanceId(chatContext.getInstanceId());
        tracingContext.setStreaming(chatContext.isStreaming());
        tracingContext.setOriginalModel(chatContext.getOriginalModel());
        tracingContext.setOriginalProvider(chatContext.getOriginalProvider());
        tracingContext.setPublicAccess(chatContext.isPublicAccess());
        tracingContext.setPublicId(chatContext.getPublicId());
        tracingContext.setPromptStableSystemSections(chatContext.getPromptStableSystemSections());
        tracingContext.setPromptDynamicSystemSections(chatContext.getPromptDynamicSystemSections());
        tracingContext.setPromptMemoryItemCount(chatContext.getPromptMemoryItemCount());
        tracingContext.setPromptCachingEnabled(chatContext.isPromptCachingEnabled());
        tracingContext.setTraceContext(chatContext.getTraceContext());

        return tracingContext;
    }

    public TraceContext getTraceContext() {
        return traceContext;
    }

    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
