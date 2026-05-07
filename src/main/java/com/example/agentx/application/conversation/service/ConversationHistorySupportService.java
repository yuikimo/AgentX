package com.example.agentx.application.conversation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.application.conversation.dto.AgentPreviewRequest;
import com.example.agentx.application.conversation.dto.MessageDTO;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.rag.RagChatContext;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.ContextDomainService;
import com.example.agentx.domain.conversation.service.ContextProcessor;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.domain.token.model.config.TokenOverflowConfig;
import com.example.agentx.domain.shared.enums.TokenOverflowStrategyEnum;
import com.example.agentx.domain.token.service.TokenEstimatorService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationHistorySupportService {
    private static final Logger logger = LoggerFactory.getLogger(ConversationHistorySupportService.class);

    private final ContextDomainService contextDomainService;
    private final MessageDomainService messageDomainService;
    private final ContextProcessor contextProcessor;
    private final ProviderConfigFactory providerConfigFactory;
    private final TokenEstimatorService tokenEstimatorService;
    private final UserModelConfigResolver userModelConfigResolver;
    private final ChatContextProperties chatContextProperties;
    private final TaskExecutor summaryTaskExecutor;

    public ConversationHistorySupportService(ContextDomainService contextDomainService,
            MessageDomainService messageDomainService, ContextProcessor contextProcessor,
            ProviderConfigFactory providerConfigFactory, TokenEstimatorService tokenEstimatorService,
            UserModelConfigResolver userModelConfigResolver, ChatContextProperties chatContextProperties,
            @Qualifier("summaryTaskExecutor") TaskExecutor summaryTaskExecutor) {
        this.contextDomainService = contextDomainService;
        this.messageDomainService = messageDomainService;
        this.contextProcessor = contextProcessor;
        this.providerConfigFactory = providerConfigFactory;
        this.tokenEstimatorService = tokenEstimatorService;
        this.userModelConfigResolver = userModelConfigResolver;
        this.chatContextProperties = chatContextProperties;
        this.summaryTaskExecutor = summaryTaskExecutor;
    }

    public void setupContextAndHistory(ChatContext environment) {
        String sessionId = environment.getSessionId();
        ContextEntity contextEntity = contextDomainService.findBySessionId(sessionId);
        List<MessageEntity> messageEntities = new ArrayList<>();

        if (contextEntity != null) {
            messageEntities = messageDomainService.listActiveMessages(contextEntity);
            if (shouldSkipTokenOverflowStrategy(environment, contextEntity, messageEntities)) {
                normalizeMessageTokenCounts(messageEntities, environment);
            } else {
                messageEntities = applyTokenOverflowStrategy(environment, contextEntity, messageEntities);
            }
        } else {
            contextEntity = new ContextEntity();
            contextEntity.setSessionId(sessionId);
        }

        environment.setContextEntity(contextEntity);
        environment.setMessageHistory(messageEntities);
    }

    public void setupWidgetContextAndHistory(ChatContext environment) {
        String sessionId = environment.getSessionId();
        ContextEntity contextEntity = contextDomainService.findBySessionId(sessionId);
        List<MessageEntity> messageEntities = new ArrayList<>();

        if (contextEntity != null) {
            messageEntities = messageDomainService.listActiveMessages(contextEntity);
            messageEntities = applyTokenOverflowStrategy(environment, contextEntity, messageEntities);
        } else {
            contextEntity = new ContextEntity();
            contextEntity.setSessionId(sessionId);
        }

        environment.setContextEntity(contextEntity);
        environment.setMessageHistory(messageEntities);
    }

    public void setupPreviewContextAndHistory(ChatContext environment, AgentPreviewRequest previewRequest) {
        ContextEntity contextEntity = new ContextEntity();
        contextEntity.setSessionId("preview-session");
        contextEntity.setActiveMessages(null);

        List<MessageEntity> messageEntities = new ArrayList<>();
        List<MessageDTO> messageHistory = previewRequest.getMessageHistory();
        if (messageHistory != null && !messageHistory.isEmpty()) {
            for (MessageDTO messageDTO : messageHistory) {
                MessageEntity messageEntity = new MessageEntity();
                messageEntity.setId(StringUtils.hasText(messageDTO.getId()) ? messageDTO.getId() : UUID.randomUUID().toString());
                messageEntity.setRole(messageDTO.getRole());
                messageEntity.setContent(messageDTO.getContent());
                messageEntity.setSessionId("preview-session");
                messageEntity.setCreatedAt(messageDTO.getCreatedAt() != null ? messageDTO.getCreatedAt() : LocalDateTime.now());
                messageEntity.setFileUrls(messageDTO.getFileUrls());
                messageEntity.setAttachments(messageDTO.getAttachments());
                messageEntities.add(messageEntity);
            }
        }
        normalizeMessageTokenCounts(messageEntities, environment);
        messageEntities = applyTokenOverflowStrategy(environment, contextEntity, messageEntities);
        environment.setContextEntity(contextEntity);
        environment.setMessageHistory(messageEntities);
    }

    public List<MessageEntity> trimRagHistoryMessages(RagChatContext ragContext, ContextEntity contextEntity,
            List<MessageEntity> messageHistory) {
        if (shouldSkipTokenOverflowStrategy(ragContext, contextEntity, messageHistory)) {
            normalizeMessageTokenCounts(messageHistory, ragContext);
            return ensureRagSummaryMessage(contextEntity, messageHistory);
        }
        List<MessageEntity> trimmedHistory = applyTokenOverflowStrategy(ragContext, contextEntity, messageHistory);
        return ensureRagSummaryMessage(contextEntity, trimmedHistory);
    }

    public List<MessageEntity> applyTokenOverflowStrategy(ChatContext environment, ContextEntity contextEntity,
            List<MessageEntity> messageEntities) {
        LLMModelConfig llmModelConfig = environment.getLlmModelConfig();
        ProviderEntity provider = environment.getProvider();

        TokenOverflowStrategyEnum strategyType = llmModelConfig.getStrategyType();
        TokenOverflowConfig tokenOverflowConfig = new TokenOverflowConfig();
        tokenOverflowConfig.setStrategyType(strategyType);
        tokenOverflowConfig.setMaxTokens(llmModelConfig.getMaxTokens());
        tokenOverflowConfig.setSummaryThreshold(llmModelConfig.getSummaryThreshold());
        tokenOverflowConfig.setReserveRatio(llmModelConfig.getReserveRatio());
        tokenOverflowConfig.setProviderConfig(resolveSummaryProviderConfig(environment, provider));
        tokenOverflowConfig.setSummaryGenerationWaitMs(chatContextProperties.getSummary().getGenerationWaitMs());
        tokenOverflowConfig.setSummaryExecutor(summaryTaskExecutor);

        normalizeMessageTokenCounts(messageEntities, environment);
        return contextProcessor.applyTokenOverflow(contextEntity, messageEntities, tokenOverflowConfig,
                environment.getSessionId()).getMessageEntities();
    }

    public void normalizeMessageTokenCounts(List<MessageEntity> messageEntities, ChatContext environment) {
        ProviderConfig providerConfig = null;
        List<MessageEntity> repairedMessages = new ArrayList<>();
        for (MessageEntity messageEntity : messageEntities) {
            if (messageEntity == null) {
                continue;
            }
            boolean repaired = false;
            if (messageEntity.getBodyTokenCount() == null || messageEntity.getBodyTokenCount() <= 0) {
                if (providerConfig == null) {
                    providerConfig = providerConfigFactory.fromChatContext(environment);
                }
                int estimatedTokens = tokenEstimatorService.estimateTextTokenCount(messageEntity.getContent(),
                        providerConfig);
                messageEntity.setBodyTokenCount(estimatedTokens);
                repaired = true;
            }
            if (messageEntity.getTokenCount() == null || messageEntity.getTokenCount() <= 0) {
                messageEntity.setTokenCount(messageEntity.getBodyTokenCount());
                repaired = true;
            }
            if (messageEntity.getCreatedAt() == null) {
                messageEntity.setCreatedAt(LocalDateTime.now());
            }
            if (!StringUtils.hasText(messageEntity.getId())) {
                messageEntity.setId(UUID.randomUUID().toString());
            }
            if (repaired && StringUtils.hasText(messageEntity.getId())) {
                MessageEntity repairEntity = new MessageEntity();
                repairEntity.setId(messageEntity.getId());
                repairEntity.setBodyTokenCount(messageEntity.getBodyTokenCount());
                repairEntity.setTokenCount(messageEntity.getTokenCount());
                repairedMessages.add(repairEntity);
            }
        }
        if (!repairedMessages.isEmpty()) {
            messageDomainService.updateMessageTokenCounts(repairedMessages);
        }
    }

    public boolean shouldSkipTokenOverflowStrategy(ChatContext environment, ContextEntity contextEntity,
            List<MessageEntity> messageEntities) {
        if (messageEntities == null || messageEntities.isEmpty()) {
            return true;
        }
        if (contextEntity != null && StringUtils.hasText(contextEntity.getSummary())) {
            return false;
        }
        int maxHistoryMessages = resolveFastPathMaxHistoryMessages(environment);
        if (messageEntities.size() > maxHistoryMessages) {
            return false;
        }
        ProviderConfig providerConfig = providerConfigFactory.fromChatContext(environment);
        int maxApproxTokens = resolveFastPathMaxApproxTokens(environment);
        int approxTokens = 0;
        for (MessageEntity messageEntity : messageEntities) {
            if (messageEntity == null) {
                continue;
            }
            Integer bodyTokenCount = messageEntity.getBodyTokenCount();
            if (bodyTokenCount != null && bodyTokenCount > 0) {
                approxTokens += bodyTokenCount;
            } else {
                approxTokens += tokenEstimatorService.estimateTextTokenCount(messageEntity.getContent(), providerConfig);
            }
            if (approxTokens > maxApproxTokens) {
                return false;
            }
        }
        return true;
    }

    private ProviderConfig resolveSummaryProviderConfig(ChatContext environment, ProviderEntity currentProvider) {
        ProviderConfig fallbackConfig = providerConfigFactory.fromProviderAndModel(currentProvider, environment.getModel(),
                Duration.ofSeconds(30));

        if (environment == null || environment instanceof RagChatContext || !StringUtils.hasText(environment.getUserId())) {
            return fallbackConfig;
        }

        try {
            ModelConfig preferredModelConfig = userModelConfigResolver.getPreferredChatModelConfig(
                    environment.getUserId(), environment.getSessionId());
            return providerConfigFactory.fromModelConfig(preferredModelConfig, Duration.ofSeconds(30));
        } catch (Exception e) {
            logger.warn("解析摘要模型失败，回退当前对话模型: userId={}, sessionId={}, err={}", environment.getUserId(),
                    environment.getSessionId(), e.getMessage());
            return fallbackConfig;
        }
    }

    private List<MessageEntity> ensureRagSummaryMessage(ContextEntity contextEntity, List<MessageEntity> messageHistory) {
        if (contextEntity == null || !StringUtils.hasText(contextEntity.getSummary()) || messageHistory == null
                || messageHistory.stream().anyMatch(MessageEntity::isSummaryMessage)) {
            return messageHistory;
        }
        List<MessageEntity> summaryAndRecent = messageDomainService.listSummaryAndRecentMessages(contextEntity, 1);
        if (summaryAndRecent.isEmpty() || !summaryAndRecent.get(0).isSummaryMessage()) {
            return messageHistory;
        }
        List<MessageEntity> merged = new ArrayList<>();
        merged.add(summaryAndRecent.get(0));
        merged.addAll(messageHistory);
        return merged;
    }

    private int resolveFastPathMaxHistoryMessages(ChatContext environment) {
        int baseLimit = Math.max(1, chatContextProperties.getFastPath().getMaxHistoryMessages());
        LLMModelConfig llmModelConfig = environment != null ? environment.getLlmModelConfig() : null;
        if (llmModelConfig == null || llmModelConfig.getMaxTokens() == null || llmModelConfig.getMaxTokens() <= 0) {
            return baseLimit;
        }
        int availableTokens = resolveAvailableModelContextTokens(llmModelConfig);
        int scaledLimit = (int) Math.round(baseLimit * (availableTokens / 3000.0));
        return Math.max(4, Math.min(24, scaledLimit));
    }

    private int resolveFastPathMaxApproxTokens(ChatContext environment) {
        int baseLimit = Math.max(64, chatContextProperties.getFastPath().getMaxApproxTokens());
        LLMModelConfig llmModelConfig = environment != null ? environment.getLlmModelConfig() : null;
        if (llmModelConfig == null || llmModelConfig.getMaxTokens() == null || llmModelConfig.getMaxTokens() <= 0) {
            return baseLimit;
        }
        int availableTokens = resolveAvailableModelContextTokens(llmModelConfig);
        int scaledLimit = (int) Math.round(baseLimit * (availableTokens / 3000.0));
        return Math.max(128, Math.min(2048, scaledLimit));
    }

    private int resolveAvailableModelContextTokens(LLMModelConfig llmModelConfig) {
        if (llmModelConfig == null || llmModelConfig.getMaxTokens() == null || llmModelConfig.getMaxTokens() <= 0) {
            return 3000;
        }
        int maxTokens = llmModelConfig.getMaxTokens();
        double reserveRatio = llmModelConfig.getReserveRatio() != null ? llmModelConfig.getReserveRatio() : 0.25;
        reserveRatio = Math.max(0D, Math.min(0.9D, reserveRatio));
        int availableTokens = maxTokens - (int) Math.floor(maxTokens * reserveRatio);
        return Math.max(256, availableTokens);
    }
}
