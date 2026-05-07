package com.example.agentx.domain.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.shared.enums.TokenOverflowStrategyEnum;
import com.example.agentx.domain.token.model.TokenMessage;
import com.example.agentx.domain.token.model.TokenProcessResult;
import com.example.agentx.domain.token.model.config.TokenOverflowConfig;
import com.example.agentx.domain.token.service.TokenDomainService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 上下文处理器 负责处理对话上下文和消息列表的相关业务逻辑 */
@Service
public class ContextProcessor {

    private final ContextDomainService contextDomainService;
    private final MessageDomainService messageDomainService;
    private final TokenDomainService tokenDomainService;

    public ContextProcessor(ContextDomainService contextDomainService, MessageDomainService messageDomainService,
            TokenDomainService tokenDomainService) {
        this.contextDomainService = contextDomainService;
        this.messageDomainService = messageDomainService;
        this.tokenDomainService = tokenDomainService;
    }

    /** 处理会话上下文和消息列表
     *
     * @param sessionId 会话ID
     * @param maxTokens 最大token数
     * @param strategyType 策略类型
     * @param summaryThreshold 摘要阈值
     * @param providerConfig 提供商配置
     * @return 处理后的上下文和消息信息 */
    public ContextResult processContext(String sessionId, int maxTokens, TokenOverflowStrategyEnum strategyType,
            int summaryThreshold, ProviderConfig providerConfig) {

        ContextEntity contextEntity = contextDomainService.findBySessionId(sessionId);
        List<MessageEntity> messageEntities = new ArrayList<>();

        if (contextEntity != null) {
            // 根据消息上下文获取消息列表
            messageEntities = messageDomainService.listActiveMessages(contextEntity);

            // 尝试触发 token 策略
            List<TokenMessage> tokenMessages = tokenizeMessage(messageEntities);

            TokenOverflowConfig tokenOverflowConfig = new TokenOverflowConfig();
            tokenOverflowConfig.setStrategyType(strategyType);
            tokenOverflowConfig.setMaxTokens(maxTokens);
            tokenOverflowConfig.setSummaryThreshold(summaryThreshold);
            tokenOverflowConfig.setProviderConfig(providerConfig);
            TokenProcessResult tokenProcessResult = tokenDomainService.processMessages(tokenMessages,
                    tokenOverflowConfig);

            if (tokenProcessResult.isProcessed()) {
                // 保留后的消息列表
                List<TokenMessage> retainedMessages = tokenProcessResult.getRetainedMessages();
                if (strategyType == TokenOverflowStrategyEnum.SUMMARIZE) {
                    String newSummary = tokenProcessResult.getSummary();
                    contextEntity.setSummary(newSummary);
                }
                contextEntity.setActiveWindowStartMessageId(resolveActiveWindowStartMessageId(retainedMessages));
                contextEntity.setActiveMessages(null);
            }
        } else {
            contextEntity = new ContextEntity();
            contextEntity.setSessionId(sessionId);
        }

        return new ContextResult(contextEntity, messageEntities);
    }

    /** 对既有上下文消息应用溢出策略 */
    public ContextResult applyTokenOverflow(ContextEntity contextEntity, List<MessageEntity> messageEntities,
            TokenOverflowConfig tokenOverflowConfig, String sessionId) {
        if (contextEntity == null) {
            contextEntity = new ContextEntity();
            contextEntity.setSessionId(sessionId);
        }
        if (messageEntities == null || messageEntities.isEmpty()) {
            return new ContextResult(contextEntity, Collections.emptyList());
        }

        List<TokenMessage> tokenMessages = tokenizeMessage(messageEntities);
        TokenProcessResult result = tokenDomainService.processMessages(tokenMessages, tokenOverflowConfig);
        if (!result.isProcessed()) {
            return new ContextResult(contextEntity, messageEntities);
        }

        List<TokenMessage> retainedMessages = result.getRetainedMessages() != null ? result.getRetainedMessages()
                : Collections.emptyList();
        TokenMessage newSummaryMessage = null;
        if (tokenOverflowConfig.getStrategyType() == TokenOverflowStrategyEnum.SUMMARIZE && !retainedMessages.isEmpty()
                && Role.SUMMARY.name().equals(retainedMessages.get(0).getRole())) {
            newSummaryMessage = retainedMessages.get(0);
            contextEntity.setSummary(newSummaryMessage.getContent());
        }
        contextEntity.setActiveWindowStartMessageId(resolveActiveWindowStartMessageId(retainedMessages));
        contextEntity.setActiveMessages(null);

        Set<String> retainedMessageIdSet = retainedMessages.stream().map(TokenMessage::getId).collect(Collectors.toSet());
        List<MessageEntity> retainedEntities = messageEntities.stream()
                .filter(message -> retainedMessageIdSet.contains(message.getId()) && !message.isSummaryMessage())
                .collect(Collectors.toCollection(ArrayList::new));
        if (newSummaryMessage != null) {
            retainedEntities.add(0, summaryMessageToEntity(newSummaryMessage, sessionId));
        }
        return new ContextResult(contextEntity, retainedEntities);
    }

    /** 将消息实体转换为Token消息 */
    private List<TokenMessage> tokenizeMessage(List<MessageEntity> messageEntities) {
        return messageEntities.stream().filter(this::shouldTokenizeMessage).map(message -> {
            TokenMessage tokenMessage = new TokenMessage();
            tokenMessage.setId(message.getId());
            tokenMessage.setRole(message.getRole().name());
            tokenMessage.setContent(message.getContent());
            tokenMessage.setTokenCount(message.getTokenCount());
            tokenMessage.setBodyTokenCount(message.getBodyTokenCount());
            tokenMessage.setCreatedAt(message.getCreatedAt());
            return tokenMessage;
        }).collect(Collectors.toList());
    }

    private boolean shouldTokenizeMessage(MessageEntity message) {
        if (message == null || message.isSummaryMessage()) {
            return true;
        }
        MessageType messageType = message.getMessageType();
        return messageType == null || messageType == MessageType.TEXT;
    }

    private String resolveActiveWindowStartMessageId(List<TokenMessage> retainedMessages) {
        if (retainedMessages == null || retainedMessages.isEmpty()) {
            return null;
        }
        TokenMessage earliest = null;
        for (TokenMessage message : retainedMessages) {
            if (message == null || Role.SUMMARY.name().equals(message.getRole())
                    || !StringUtils.hasText(message.getId())) {
                continue;
            }
            if (earliest == null || compareCreatedAt(message, earliest) < 0) {
                earliest = message;
            }
        }
        return earliest != null ? earliest.getId() : null;
    }

    private int compareCreatedAt(TokenMessage left, TokenMessage right) {
        if (left == null || left.getCreatedAt() == null) {
            return right == null || right.getCreatedAt() == null ? 0 : 1;
        }
        if (right == null || right.getCreatedAt() == null) {
            return -1;
        }
        return left.getCreatedAt().compareTo(right.getCreatedAt());
    }

    private MessageEntity summaryMessageToEntity(TokenMessage tokenMessage, String sessionId) {
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setId(tokenMessage.getId());
        messageEntity.setRole(Role.fromCode(tokenMessage.getRole()));
        messageEntity.setContent(tokenMessage.getContent());
        messageEntity.setTokenCount(tokenMessage.getBodyTokenCount());
        messageEntity.setBodyTokenCount(tokenMessage.getBodyTokenCount());
        messageEntity.setCreatedAt(tokenMessage.getCreatedAt());
        messageEntity.setSessionId(sessionId);
        messageEntity.setMessageType(MessageType.TEXT);
        return messageEntity;
    }

    /** 上下文处理结果 */
    public static class ContextResult {
        private final ContextEntity contextEntity;
        private final List<MessageEntity> messageEntities;

        public ContextResult(ContextEntity contextEntity, List<MessageEntity> messageEntities) {
            this.contextEntity = contextEntity;
            this.messageEntities = messageEntities;
        }

        public ContextEntity getContextEntity() {
            return contextEntity;
        }

        public List<MessageEntity> getMessageEntities() {
            return messageEntities;
        }
    }
}
