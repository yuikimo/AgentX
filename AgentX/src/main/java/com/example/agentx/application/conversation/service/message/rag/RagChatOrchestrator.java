package com.example.agentx.application.conversation.service.message.rag;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.prompt.RagPromptTemplates;
import com.example.agentx.infrastructure.transport.MessageTransport;

@Component
public class RagChatOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(RagChatOrchestrator.class);

    private final RagRetrievalCoordinator ragRetrievalCoordinator;
    private final RagAnswerGenerator ragAnswerGenerator;

    public RagChatOrchestrator(RagRetrievalCoordinator ragRetrievalCoordinator, RagAnswerGenerator ragAnswerGenerator) {
        this.ragRetrievalCoordinator = ragRetrievalCoordinator;
        this.ragAnswerGenerator = ragAnswerGenerator;
    }

    public <T> void processStreamingChat(RagChatContext ragContext, T connection, MessageTransport<T> transport,
            MessageEntity userEntity, MessageEntity llmEntity, MessageWindowChatMemory memory,
            ToolProvider toolProvider, RagAnswerGenerator.RuntimeSupport runtimeSupport) {
        try {
            RagRetrievalResult retrievalResult = ragRetrievalCoordinator.performRetrieval(ragContext, transport, connection);
            if (runtimeSupport.isInterrupted(ragContext)) {
                finishInterrupted(ragContext, retrievalResult, connection, transport, userEntity, runtimeSupport);
                return;
            }
            if (!retrievalResult.isSuccess()) {
                finishEarly(ragContext, retrievalResult, connection, transport, userEntity, llmEntity,
                        retrievalResult.getResultMessage(), MessageType.ERROR, false, runtimeSupport);
                return;
            }
            if (!retrievalResult.hasDocuments()) {
                finishEarly(ragContext, retrievalResult, connection, transport, userEntity, llmEntity,
                        RagPromptTemplates.noDocumentsMessage(), MessageType.TEXT, true, runtimeSupport);
                return;
            }
            ragAnswerGenerator.generateAnswer(ragContext, retrievalResult, connection, transport, userEntity, llmEntity,
                    memory, toolProvider, runtimeSupport);
        } catch (Exception e) {
            logger.error("RAG流式处理失败", e);
            AgentChatResponse errorResponse = ChatErrorResponseFactory.fromThrowable(e);
            transport.sendEndMessage(connection, errorResponse);
            runtimeSupport.onChatError(ragContext, e);
            runtimeSupport.onChatCompleted(ragContext, false, e.getMessage());
            transport.completeConnection(connection);
        }
    }

    private <T> void finishInterrupted(RagChatContext ragContext, RagRetrievalResult retrievalResult, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, RagAnswerGenerator.RuntimeSupport runtimeSupport) {
        try {
            ragAnswerGenerator.persistUserMessage(ragContext, userEntity, retrievalResult, runtimeSupport);
            runtimeSupport.onChatCompleted(ragContext, false, "interrupted");
        } finally {
            transport.completeConnection(connection);
        }
    }

    private <T> void finishEarly(RagChatContext ragContext, RagRetrievalResult retrievalResult, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity, String content,
            MessageType messageType, boolean success, RagAnswerGenerator.RuntimeSupport runtimeSupport) {
        try {
            ragAnswerGenerator.persistUserMessage(ragContext, userEntity, retrievalResult, runtimeSupport);
            ragAnswerGenerator.persistEarlyAssistantMessage(ragContext, retrievalResult, llmEntity, content, messageType,
                    runtimeSupport);
            if (messageType == MessageType.ERROR) {
                transport.sendEndMessage(connection,
                        ChatErrorResponseFactory.build(ChatErrorResponseFactory.CODE_INTERNAL_ERROR, content));
            } else {
                transport.sendEndMessage(connection, AgentChatResponse.build(content, messageType));
            }
            runtimeSupport.onChatCompleted(ragContext, success, success ? null : content);
            if (success) {
                runtimeSupport.afterAnswerCompleted(ragContext);
            }
        } finally {
            transport.completeConnection(connection);
        }
    }
}
