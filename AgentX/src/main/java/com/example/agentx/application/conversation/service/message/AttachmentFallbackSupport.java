package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.trace.model.ModelCallInfo;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.ChatResponseTokenUsageUtils;
import com.example.agentx.infrastructure.transport.MessageTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AttachmentFallbackSupport {
    private static final Logger logger = LoggerFactory.getLogger(AttachmentFallbackSupport.class);

    public <T> boolean tryRecoverSync(AbstractMessageHandler handler, ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, Exception exception, long startTime) {
        if (!shouldRetryWithImageFallback(handler, chatContext, exception)) {
            return false;
        }
        logger.warn("检测到图片输入协议不兼容，尝试自动降级重试: sessionId={}, err={}", chatContext.getSessionId(),
                exception.getMessage());
        try {
            ChatResponse fallbackResponse = executeFallbackChat(handler, chatContext, userEntity, memory);
            completeFallbackSyncResponse(handler, chatContext, connection, transport, userEntity, llmEntity,
                    fallbackResponse, startTime);
            return true;
        } catch (Exception retryException) {
            logger.warn("图片降级重试失败: sessionId={}, err={}", chatContext.getSessionId(), retryException.getMessage());
            return false;
        }
    }

    public <T> boolean tryRecoverStreaming(AbstractMessageHandler handler, ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            MessageWindowChatMemory memory, Throwable throwable, long startTime) {
        if (!shouldRetryWithImageFallback(handler, chatContext, throwable)) {
            return false;
        }
        logger.warn("流式调用检测到图片输入协议不兼容，尝试自动降级重试: sessionId={}, err={}", chatContext.getSessionId(),
                throwable.getMessage());
        try {
            ChatResponse fallbackResponse = executeFallbackChat(handler, chatContext, userEntity, memory);
            completeFallbackStreamingResponse(handler, chatContext, connection, transport, userEntity, llmEntity,
                    fallbackResponse, startTime);
            return true;
        } catch (Exception retryException) {
            logger.warn("流式图片降级重试失败: sessionId={}, err={}", chatContext.getSessionId(), retryException.getMessage());
            return false;
        }
    }

    public ChatResponse executeSyncFallbackChat(AbstractMessageHandler handler, ChatContext chatContext,
            MessageWindowChatMemory memory) {
        ChatModel syncClient = handler.llmServiceFactory.getStrandClient(chatContext.getProvider(),
                chatContext.getModel(), handler.resolveSyncModelTimeout(chatContext));
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        messages.add(handler.buildCurrentUserMessage(chatContext));
        return handler.invokeSyncChatWithFriendlyError(chatContext, syncClient, messages);
    }

    public <T> void completeFallbackStreamingResponse(AbstractMessageHandler handler, ChatContext chatContext,
            T connection, MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity,
            ChatResponse chatResponse, long startTime) {
        handler.setMessageTokenCount(chatContext, chatContext.getMessageHistory(), userEntity, llmEntity, chatResponse);
        handler.updateCurrentAssistantReply(chatContext, chatResponse);
        handler.prepareContextForCurrentTurn(chatContext, userEntity);
        handler.messageDomainService.finalizeTurn(chatContext.getContextEntity(), Collections.singletonList(userEntity),
                Collections.singletonList(llmEntity));

        if (chatResponse.aiMessage() != null && StringUtils.isNotBlank(chatResponse.aiMessage().text())) {
            transport.sendMessage(connection, AgentChatResponse.build(chatResponse.aiMessage().text(), MessageType.TEXT));
        }
        transport.sendEndMessage(connection, AgentChatResponse.buildEndMessage(MessageType.TEXT));

        long latency = System.currentTimeMillis() - startTime;
        handler.highAvailabilityDomainService.reportCallResult(chatContext.getInstanceId(), chatContext.getModel().getId(),
                true, latency, null);

        ModelCallInfo modelCallInfo = handler.buildModelCallInfo(chatContext, chatResponse, latency, true);
        handler.onModelCallCompleted(chatContext, chatResponse, modelCallInfo);

        handler.performBillingWithErrorHandling(chatContext, ChatResponseTokenUsageUtils.inputTokenCount(chatResponse),
                ChatResponseTokenUsageUtils.outputTokenCount(chatResponse), transport, connection);
        handler.onChatCompleted(chatContext, true, null);
        handler.smartRenameSession(chatContext);
    }

    public Throwable normalizeModelCallThrowable(AbstractMessageHandler handler, ChatContext chatContext,
            Throwable throwable) {
        if (throwable instanceof Exception exception) {
            return normalizeModelCallException(handler, chatContext, exception);
        }
        return throwable;
    }

    public RuntimeException normalizeModelCallException(AbstractMessageHandler handler, ChatContext chatContext,
            Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException;
        }
        if (handler.providerErrorClassifier.classify(exception) != ProviderErrorClassifier.ProviderErrorType.HTML_RESPONSE) {
            if (exception instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            return new BusinessException(StringUtils.defaultIfBlank(exception.getMessage(), "模型调用失败"));
        }
        String baseUrl = null;
        if (chatContext != null && chatContext.getProvider() != null && chatContext.getProvider().getConfig() != null) {
            baseUrl = chatContext.getProvider().getConfig().getBaseUrl();
        }
        String detail = StringUtils.isNotBlank(baseUrl) ? " 当前baseUrl: " + baseUrl : "";
        return new BusinessException("模型服务返回了HTML页面而不是JSON，请检查服务商基础URL配置（通常应为API地址并包含 /v1）。" + detail);
    }

    private boolean shouldRetryWithImageFallback(AbstractMessageHandler handler, ChatContext chatContext,
            Throwable throwable) {
        if (chatContext == null || chatContext.isImageFallbackApplied()
                || !handler.conversationAttachmentService.hasImageAttachments(chatContext.getAttachments())) {
            return false;
        }
        return handler.providerErrorClassifier.classify(throwable)
                == ProviderErrorClassifier.ProviderErrorType.UNSUPPORTED_IMAGE;
    }

    private ChatResponse executeFallbackChat(AbstractMessageHandler handler, ChatContext chatContext,
            MessageEntity userEntity, MessageWindowChatMemory memory) {
        chatContext.setImageFallbackApplied(true);
        List<ConversationAttachment> fallbackAttachments = handler.conversationAttachmentService
                .buildImageFallbackSummaries(chatContext.getUserId(), chatContext.getAttachments());
        chatContext.setAttachments(fallbackAttachments);
        chatContext.setFileUrls(handler.extractAttachmentUrls(fallbackAttachments));
        handler.applyAttachmentsToMessage(userEntity, fallbackAttachments);

        ChatModel syncClient = handler.llmServiceFactory.getStrandClient(chatContext.getProvider(),
                chatContext.getModel(), handler.resolveSyncModelTimeout(chatContext));
        List<ChatMessage> messages = stripImageContents(memory.messages());
        messages.add(handler.buildCurrentUserMessage(chatContext));
        return handler.invokeSyncChatWithFriendlyError(chatContext, syncClient, messages);
    }

    private List<ChatMessage> stripImageContents(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> sanitizedMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (!(message instanceof UserMessage userMessage) || userMessage.contents() == null
                    || userMessage.contents().stream().noneMatch(ImageContent.class::isInstance)) {
                sanitizedMessages.add(message);
                continue;
            }
            List<Content> textContents = new ArrayList<>();
            for (Content content : userMessage.contents()) {
                if (content instanceof TextContent textContent && StringUtils.isNotBlank(textContent.text())) {
                    textContents.add(TextContent.from(textContent.text()));
                }
            }
            sanitizedMessages.add(textContents.isEmpty() ? UserMessage.from("[历史图片已在降级重试时移除多模态内容]")
                    : UserMessage.from(textContents));
        }
        return sanitizedMessages;
    }

    private <T> void completeFallbackSyncResponse(AbstractMessageHandler handler, ChatContext chatContext, T connection,
            MessageTransport<T> transport, MessageEntity userEntity, MessageEntity llmEntity, ChatResponse chatResponse,
            long startTime) {
        handler.setMessageTokenCount(chatContext, chatContext.getMessageHistory(), userEntity, llmEntity, chatResponse);
        handler.updateCurrentAssistantReply(chatContext, chatResponse);
        ModelCallInfo modelCallInfo = handler.buildModelCallInfo(chatContext, chatResponse,
                System.currentTimeMillis() - startTime, true);
        handler.onModelCallCompleted(chatContext, chatResponse, modelCallInfo);

        handler.prepareContextForCurrentTurn(chatContext, userEntity);
        handler.messageDomainService.finalizeTurn(chatContext.getContextEntity(), Collections.singletonList(userEntity),
                Collections.singletonList(llmEntity));

        AgentChatResponse response = new AgentChatResponse(chatResponse.aiMessage().text(), true);
        response.setMessageType(MessageType.TEXT);
        transport.sendEndMessage(connection, response);

        long latency = System.currentTimeMillis() - startTime;
        handler.highAvailabilityDomainService.reportCallResult(chatContext.getInstanceId(), chatContext.getModel().getId(),
                true, latency, null);

        handler.performBillingWithErrorHandling(chatContext, ChatResponseTokenUsageUtils.inputTokenCount(chatResponse),
                ChatResponseTokenUsageUtils.outputTokenCount(chatResponse), transport, connection);
        handler.onChatCompleted(chatContext, true, null);
    }
}
