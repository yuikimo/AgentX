package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.trace.model.ModelCallInfo;
import com.example.agentx.domain.trace.model.ToolCallInfo;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.ChatResponseTokenUsageUtils;
import com.example.agentx.infrastructure.transport.MessageTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class StreamingChatExecutor {
    private static final Logger logger = LoggerFactory.getLogger(StreamingChatExecutor.class);

    public <T> void execute(AbstractMessageHandler handler, ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, ToolProvider toolProvider) {
        StreamingChatModel streamingClient = handler.llmServiceFactory.getStreamingClient(chatContext.getProvider(),
                chatContext.getModel(), handler.resolveStreamingModelTimeout(chatContext));

        List<CapturedToolExecution> capturedToolExecutions = handler.toolExecutionSupport.newCaptureBuffer();
        ToolProgressStreamState toolProgressStreamState = new ToolProgressStreamState();
        ToolExecutionProgressListener progressListener = handler.buildToolExecutionProgressListener(connection, transport,
                chatContext, toolProgressStreamState);
        Agent agent = handler.buildStreamingAgent(streamingClient, memory, toolProvider, chatContext.getAgent(),
                capturedToolExecutions, progressListener);

        handler.beginStreamingToolCapture(capturedToolExecutions);
        try {
            executeTokenStream(handler, agent, connection, transport, chatContext, userEntity, llmEntity, memory,
                    toolProvider, toolProgressStreamState);
        } finally {
            handler.clearStreamingToolCapture();
        }
    }

    <T> void executeTokenStream(AbstractMessageHandler handler, Agent agent, T connection,
            MessageTransport<T> transport, ChatContext chatContext, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, ToolProvider toolProvider, ToolProgressStreamState toolProgressStreamState) {
        handler.saveUserMessage(chatContext, userEntity);

        AtomicReference<StringBuilder> messageBuilder = new AtomicReference<>(new StringBuilder());
        List<MessageEntity> bufferedMessages = Collections.synchronizedList(new ArrayList<>());
        List<CapturedToolExecution> capturedToolExecutions = handler.currentStreamingToolCapture();
        TokenStream tokenStream = agent.chat(handler.buildCurrentUserMessage(chatContext));
        Map<String, String> callbackMdc = MDC.getCopyOfContextMap();
        long startTime = System.currentTimeMillis();

        tokenStream.onError(throwable -> handler.runWithMdc(callbackMdc, () -> {
            if (handler.isChatInterrupted(chatContext)) {
                handler.completeInterruptedChat(chatContext, connection, transport);
                return;
            }
            boolean fallbackHandled = handler.tryRecoverStreamingWithAttachmentFallback(chatContext, connection, transport,
                    userEntity, llmEntity, memory, throwable, startTime);
            if (fallbackHandled) {
                transport.completeConnection(connection);
                return;
            }
            Throwable handledThrowable = handler.normalizeModelCallThrowable(chatContext, throwable);
            try {
                handler.persistPartialStreamingMessagesOnError(chatContext, userEntity, messageBuilder.get(),
                        bufferedMessages);

                transport.sendEndMessage(connection,
                        com.example.agentx.application.conversation.util.ChatErrorResponseFactory.fromThrowable(handledThrowable));

                long latency = System.currentTimeMillis() - startTime;
                handler.highAvailabilityDomainService.reportCallResult(chatContext.getInstanceId(),
                        chatContext.getModel().getId(), false, latency, handledThrowable.getMessage());

                handler.onChatError(chatContext, com.example.agentx.domain.trace.constant.ExecutionPhase.MODEL_CALL,
                        handledThrowable);
                handler.onChatCompleted(chatContext, false, handledThrowable.getMessage());
            } finally {
                transport.completeConnection(connection);
            }
        }));

        tokenStream.onPartialResponse(reply -> handler.runWithMdc(callbackMdc, () -> {
            if (handler.isChatInterrupted(chatContext)) {
                return;
            }
            messageBuilder.get().append(reply);
            if (messageBuilder.get().toString().trim().isEmpty()) {
                return;
            }
            transport.sendMessage(connection, AgentChatResponse.build(reply, MessageType.TEXT));
        }));

        tokenStream.onCompleteResponse(chatResponse -> handler.runWithMdc(callbackMdc, () -> {
            try {
                if (handler.isChatInterrupted(chatContext)) {
                    handler.completeInterruptedChat(chatContext, connection, transport);
                    return;
                }
                if (handler.isEmptyStreamingResponse(chatResponse, messageBuilder.get())) {
                    logger.warn("流式模型返回空文本，尝试同步兜底: sessionId={}, model={}", chatContext.getSessionId(),
                            chatContext.getModel().getModelId());
                    ChatResponse fallbackResponse = handler.executeSyncFallbackChat(chatContext, memory);
                    if (isBlankAssistantResponse(fallbackResponse)) {
                        throw new BusinessException("模型返回空响应，请稍后重试或切换模型");
                    }
                    handler.completeFallbackStreamingResponse(chatContext, connection, transport, userEntity, llmEntity,
                            fallbackResponse, startTime);
                    return;
                }

                handler.setMessageTokenCount(chatContext, chatContext.getMessageHistory(), userEntity, llmEntity,
                        chatResponse);
                handler.prepareContextForCurrentTurn(chatContext, userEntity);
                List<MessageEntity> messagesToSave = handler.drainBufferedMessages(bufferedMessages);
                messagesToSave.add(llmEntity);
                handler.messageDomainService.finalizeTurn(chatContext.getContextEntity(),
                        Collections.singletonList(userEntity), messagesToSave);

                transport.sendEndMessage(connection, AgentChatResponse.buildEndMessage(MessageType.TEXT));

                long latency = System.currentTimeMillis() - startTime;
                handler.highAvailabilityDomainService.reportCallResult(chatContext.getInstanceId(),
                        chatContext.getModel().getId(), true, latency, null);

                handler.updateCurrentAssistantReply(chatContext, chatResponse);
                ModelCallInfo modelCallInfo = handler.buildModelCallInfo(chatContext, chatResponse, latency, true);
                handler.onModelCallCompleted(chatContext, chatResponse, modelCallInfo);

                handler.performBillingWithErrorHandling(chatContext,
                        ChatResponseTokenUsageUtils.inputTokenCount(chatResponse),
                        ChatResponseTokenUsageUtils.outputTokenCount(chatResponse), transport, connection);

                handler.onChatCompleted(chatContext, true, null);
                handler.smartRenameSession(chatContext);
            } finally {
                transport.completeConnection(connection);
            }
        }));

        tokenStream.onToolExecuted(toolExecution -> handler.runWithMdc(callbackMdc, () -> {
            if (handler.isChatInterrupted(chatContext)) {
                return;
            }
            emitBufferedAssistantSegment(handler, connection, transport, chatContext, messageBuilder, bufferedMessages,
                    toolProgressStreamState);
            emitToolExecution(handler, connection, transport, chatContext, bufferedMessages, capturedToolExecutions,
                    toolExecution);
        }));

        tokenStream.start();
    }

    private <T> void emitBufferedAssistantSegment(AbstractMessageHandler handler, T connection,
            MessageTransport<T> transport, ChatContext chatContext, AtomicReference<StringBuilder> messageBuilder,
            List<MessageEntity> bufferedMessages, ToolProgressStreamState toolProgressStreamState) {
        if (messageBuilder.get().isEmpty()) {
            if (toolProgressStreamState != null) {
                toolProgressStreamState.consumeTextSegmentFlushed();
            }
            return;
        }
        boolean alreadyFlushed = toolProgressStreamState != null && toolProgressStreamState.consumeTextSegmentFlushed();
        if (!alreadyFlushed) {
            transport.sendMessage(connection, AgentChatResponse.buildEndMessage(MessageType.TEXT));
        }
        MessageEntity assistantSegment = handler.createLlmMessage(chatContext);
        assistantSegment.setContent(messageBuilder.get().toString());
        handler.populateAssistantMessageTokenEstimate(chatContext, assistantSegment);
        bufferedMessages.add(assistantSegment);
        messageBuilder.set(new StringBuilder());
    }

    private <T> void emitToolExecution(AbstractMessageHandler handler, T connection, MessageTransport<T> transport,
            ChatContext chatContext, List<MessageEntity> bufferedMessages,
            List<CapturedToolExecution> capturedToolExecutions, ToolExecution toolExecution) {
        CapturedToolExecution capturedToolExecution = handler.toolExecutionSupport
                .findAndRemoveMatchingExecution(capturedToolExecutions, toolExecution);
        Integer durationMs = capturedToolExecution != null ? capturedToolExecution.executionTime() : null;
        String message = "执行工具：" + toolExecution.request().name();
        MessageEntity toolMessage = handler.createLlmMessage(chatContext);
        toolMessage.setMessageType(MessageType.TOOL_CALL);
        toolMessage.setContent(message);
        toolMessage.setMetadata(
                handler.buildToolExecutionPayload(toolExecution.request().arguments(), toolExecution.result(), durationMs));
        handler.populateAssistantMessageTokenEstimate(chatContext, toolMessage);
        bufferedMessages.add(toolMessage);

        transport.sendMessage(connection, handler.buildToolExecutionResponse(message, toolExecution.request().arguments(),
                toolExecution.result(), durationMs));

        ToolCallInfo toolCallInfo = handler.buildToolCallInfo(toolExecution, durationMs);
        handler.onToolCallCompleted(chatContext, toolCallInfo);
    }

    private boolean isBlankAssistantResponse(ChatResponse chatResponse) {
        return chatResponse == null || chatResponse.aiMessage() == null || chatResponse.aiMessage().text() == null
                || chatResponse.aiMessage().text().trim().isEmpty();
    }
}
