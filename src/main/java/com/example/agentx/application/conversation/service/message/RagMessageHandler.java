package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.stereotype.Component;
import com.example.agentx.application.billing.service.BillingService;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.application.conversation.service.ChatSessionManager;
import com.example.agentx.application.conversation.service.RagSessionManager;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.builtin.BuiltInToolRegistry;
import com.example.agentx.application.conversation.service.message.rag.RagAnswerGenerator;
import com.example.agentx.application.conversation.service.message.rag.RagChatOrchestrator;
import com.example.agentx.application.conversation.service.message.rag.RagChatContext;
import com.example.agentx.application.conversation.service.message.Agent;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.trace.constant.ExecutionPhase;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.AccountDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.transport.MessageTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

/** RAG专用的消息处理器 继承AbstractMessageHandler，添加RAG检索和问答的特定逻辑 */
@Component("ragMessageHandler")
public class RagMessageHandler extends AbstractMessageHandler implements RagAnswerGenerator.RuntimeSupport {

    private static final Logger logger = LoggerFactory.getLogger(RagMessageHandler.class);

    private final RagChatOrchestrator ragChatOrchestrator;

    public RagMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService, SessionDomainService sessionDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            BuiltInToolRegistry builtInToolRegistry, BillingService billingService,
            AccountDomainService accountDomainService, ChatSessionManager chatSessionManager,
            RagChatOrchestrator ragChatOrchestrator, ProviderConfigFactory providerConfigFactory,
            ChatContextProperties chatContextProperties) {
        super(llmServiceFactory, messageDomainService, highAvailabilityDomainService, sessionDomainService,
                userSettingsDomainService, llmDomainService, builtInToolRegistry, billingService, accountDomainService,
                chatSessionManager, providerConfigFactory, chatContextProperties);
        this.ragChatOrchestrator = ragChatOrchestrator;
    }

    /** 重写流式聊天处理，添加RAG检索逻辑 */
    @Override
    protected <T> void processStreamingChat(ChatContext chatContext, T connection, MessageTransport<T> transport,
            MessageEntity userEntity, MessageEntity llmEntity, MessageWindowChatMemory memory,
            ToolProvider toolProvider) {

        // 检查是否是RAG上下文
        if (!(chatContext instanceof RagChatContext)) {
            throw new IllegalArgumentException("RagMessageHandler requires RagChatContext");
        }
        ragChatOrchestrator.processStreamingChat((RagChatContext) chatContext, connection, transport, userEntity, llmEntity,
                memory, toolProvider, this);
    }

    @Override
    public Agent buildStreamingAgent(StreamingChatModel model, MessageWindowChatMemory memory, ToolProvider toolProvider,
            RagChatContext ragChatContext) {
        return super.buildStreamingAgent(model, memory, toolProvider, ragChatContext.getAgent(),
                Collections.synchronizedList(new ArrayList<>()), null);
    }

    @Override
    public UserMessage buildUserMessage(String text, RagChatContext ragChatContext) {
        return super.buildUserMessage(text, ragChatContext.getAttachments(), !ragChatContext.isImageFallbackApplied());
    }

    @Override
    public void performBilling(RagChatContext ragChatContext, Integer inputTokens, Integer outputTokens,
            MessageTransport<?> transport, Object connection) {
        @SuppressWarnings("unchecked")
        MessageTransport<Object> castTransport = (MessageTransport<Object>) transport;
        performBillingWithErrorHandling(ragChatContext, inputTokens, outputTokens, castTransport, connection);
    }

    @Override
    public void onChatCompleted(RagChatContext ragChatContext, boolean success, String errorMessage) {
        super.onChatCompleted(ragChatContext, success, errorMessage);
    }

    @Override
    public void onChatError(RagChatContext ragChatContext, Throwable throwable) {
        super.onChatError(ragChatContext, ExecutionPhase.MODEL_CALL, throwable);
    }

    @Override
    public void afterAnswerCompleted(RagChatContext ragChatContext) {
        if (!Objects.equals(RagSessionManager.RAG_AGENT_ID, ragChatContext.getAgent().getId())) {
            smartRenameSession(ragChatContext);
        }
    }

    @Override
    public int estimateMessageBodyTokens(RagChatContext ragChatContext, String content) {
        return super.estimateMessageBodyTokens(ragChatContext, content);
    }

    @Override
    public void onPersistPartialFailure(RagChatContext ragChatContext, Exception e) {
        logger.error("保存RAG流式失败时的部分响应失败: sessionId={}, userId={}, err={}",
                ragChatContext.getSessionId(), ragChatContext.getUserId(), e.getMessage(), e);
    }

    @Override
    public boolean isInterrupted(RagChatContext ragChatContext) {
        return super.isChatInterrupted(ragChatContext);
    }
}
