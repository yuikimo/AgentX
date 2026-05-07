package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.trace.constant.ExecutionPhase;
import com.example.agentx.domain.trace.model.ModelCallInfo;
import com.example.agentx.infrastructure.llm.ChatResponseTokenUsageUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class SyncChatExecutor {
    public <T> void execute(AbstractMessageHandler handler, ChatContext chatContext, T connection,
            com.example.agentx.infrastructure.transport.MessageTransport<T> transport, MessageEntity userEntity,
            MessageEntity llmEntity, MessageWindowChatMemory memory, ToolProvider toolProvider) {

        ChatModel syncClient = handler.llmServiceFactory.getStrandClient(chatContext.getProvider(),
                chatContext.getModel(), handler.resolveSyncModelTimeout(chatContext));
        handler.saveUserMessage(chatContext, userEntity);
        long startTime = System.currentTimeMillis();

        try {
            List<CapturedToolExecution> capturedToolExecutions = handler.toolExecutionSupport.newCaptureBuffer();
            ChatResponse chatResponse;
            if (handler.hasAvailableTools(chatContext, toolProvider)) {
                SyncAgent syncAgent = handler.buildSyncAgent(syncClient, memory, toolProvider, chatContext.getAgent(),
                        capturedToolExecutions);
                chatResponse = handler.buildSyntheticChatResponse(syncAgent.chat(handler.buildCurrentUserMessage(chatContext)));
            } else {
                List<ChatMessage> messages = memory.messages();
                messages.add(handler.buildCurrentUserMessage(chatContext));
                chatResponse = handler.invokeSyncChatWithFriendlyError(chatContext, syncClient, messages);
            }

            handler.setMessageTokenCount(chatContext, chatContext.getMessageHistory(), userEntity, llmEntity,
                    chatResponse);
            List<MessageEntity> toolMessages = handler.buildToolMessagesFromExecutions(chatContext,
                    capturedToolExecutions);

            handler.updateCurrentAssistantReply(chatContext, chatResponse);
            ModelCallInfo modelCallInfo = handler.buildModelCallInfo(chatContext, chatResponse,
                    System.currentTimeMillis() - startTime, true);
            handler.onModelCallCompleted(chatContext, chatResponse, modelCallInfo);
            capturedToolExecutions
                    .forEach(execution -> handler.onToolCallCompleted(chatContext, handler.buildToolCallInfo(execution)));

            handler.prepareContextForCurrentTurn(chatContext, userEntity);
            List<MessageEntity> assistantMessages = new ArrayList<>(toolMessages);
            assistantMessages.add(llmEntity);
            handler.messageDomainService.finalizeTurn(chatContext.getContextEntity(), Collections.singletonList(userEntity),
                    assistantMessages);

            AgentChatResponse response = new AgentChatResponse(chatResponse.aiMessage().text(), true);
            response.setMessageType(MessageType.TEXT);
            transport.sendEndMessage(connection, response);

            long latency = System.currentTimeMillis() - startTime;
            handler.highAvailabilityDomainService.reportCallResult(chatContext.getInstanceId(),
                    chatContext.getModel().getId(), true, latency, null);

            Integer billedInputTokens = Optional.ofNullable(ChatResponseTokenUsageUtils.inputTokenCount(chatResponse))
                    .orElse(userEntity.getTokenCount());
            Integer billedOutputTokens = Optional.ofNullable(ChatResponseTokenUsageUtils.outputTokenCount(chatResponse))
                    .orElse(llmEntity.getTokenCount());
            handler.performBillingWithErrorHandling(chatContext, billedInputTokens, billedOutputTokens, transport,
                    connection);

            handler.onChatCompleted(chatContext, true, null);

        } catch (Exception e) {
            if (handler.tryRecoverSyncWithAttachmentFallback(chatContext, connection, transport, userEntity, llmEntity,
                    memory, e, startTime)) {
                return;
            }
            RuntimeException handledException = handler.normalizeModelCallException(chatContext, e);
            transport.sendMessage(connection, com.example.agentx.application.conversation.util.ChatErrorResponseFactory
                    .fromThrowable(handledException));

            long latency = System.currentTimeMillis() - startTime;
            handler.highAvailabilityDomainService.reportCallResult(chatContext.getInstanceId(),
                    chatContext.getModel().getId(), false, latency, handledException.getMessage());

            handler.prepareContextForCurrentTurn(chatContext, userEntity);
            handler.messageDomainService.finalizeTurn(chatContext.getContextEntity(), Collections.singletonList(userEntity),
                    Collections.emptyList());
            handler.onChatError(chatContext, ExecutionPhase.MODEL_CALL, handledException);
            handler.onChatCompleted(chatContext, false, handledException.getMessage());
        }
    }
}
