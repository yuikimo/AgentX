package com.example.agentx.application.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import com.example.agentx.application.conversation.dto.RagChatRequest;
import com.example.agentx.application.conversation.service.message.rag.RagChatContext;
import com.example.agentx.application.rag.dto.RagSessionDTO;
import com.example.agentx.application.rag.dto.RagStreamChatRequest;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.ContextDomainService;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.model.HighAvailabilityResult;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.prompt.RagPromptTemplates;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class RagConversationAppService {
    private static final AgentEntity RAG_AGENT_TEMPLATE = createRagAgentTemplate();

    private final RagSessionManager ragSessionManager;
    private final SessionDomainService sessionDomainService;
    private final ContextDomainService contextDomainService;
    private final MessageDomainService messageDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final LLMDomainService llmDomainService;
    private final HighAvailabilityDomainService highAvailabilityDomainService;
    private final LLMModelConfigFactory llmModelConfigFactory;
    private final ConversationHistorySupportService historySupportService;
    private final TaskExecutor ragSearchGroupTaskExecutor;

    public RagConversationAppService(RagSessionManager ragSessionManager, SessionDomainService sessionDomainService,
            ContextDomainService contextDomainService, MessageDomainService messageDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService, LLMModelConfigFactory llmModelConfigFactory,
            ConversationHistorySupportService historySupportService,
            @Qualifier("ragSearchGroupTaskExecutor") TaskExecutor ragSearchGroupTaskExecutor) {
        this.ragSessionManager = ragSessionManager;
        this.sessionDomainService = sessionDomainService;
        this.contextDomainService = contextDomainService;
        this.messageDomainService = messageDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.llmModelConfigFactory = llmModelConfigFactory;
        this.historySupportService = historySupportService;
        this.ragSearchGroupTaskExecutor = ragSearchGroupTaskExecutor;
    }

    public RagChatRequest buildRagStreamChatRequest(RagStreamChatRequest request, String userId) {
        String sessionId = resolveRagSessionId(request, userId);
        return RagChatRequest.fromRagStreamChatRequest(request, sessionId);
    }

    public RagChatRequest buildUserRagStreamChatRequest(RagStreamChatRequest request, String userRagId, String userId) {
        String sessionId = resolveUserRagSessionId(request, userRagId, userId);
        return RagChatRequest.fromRagStreamChatRequestWithUserRag(request, userRagId, sessionId);
    }

    public RagSessionDTO createNewRagSession(String userId) {
        return new RagSessionDTO(ragSessionManager.createNewRagSessionForUser(userId));
    }

    public RagSessionDTO createNewUserRagSession(String userRagId, String userId) {
        return new RagSessionDTO(ragSessionManager.createNewUserRagSessionForUser(userId, userRagId));
    }

    public void closeRagSession(String sessionId, String userId) {
        validateRagSessionOwnership(sessionId, userId);
        ragSessionManager.closeSession(userId, sessionId);
    }

    public RagChatContext prepareRagEnvironment(RagChatRequest ragRequest, String userId) {
        String sessionId = ragRequest.getSessionId();
        CompletableFuture<ContextEntity> contextFuture = supplyAsync(() -> contextDomainService.findBySessionId(sessionId));
        CompletableFuture<List<MessageEntity>> messageHistoryFuture = contextFuture.thenApplyAsync(context -> {
            if (context == null) {
                return new ArrayList<>();
            }
            return messageDomainService.listActiveMessages(context);
        }, ragSearchGroupTaskExecutor);
        CompletableFuture<String> defaultModelIdFuture = supplyAsync(
                () -> userSettingsDomainService.getUserDefaultModelId(userId));
        CompletableFuture<List<String>> fallbackChainFuture = supplyAsync(
                () -> userSettingsDomainService.getUserFallbackChain(userId));
        CompletableFuture<ModelEntity> modelFuture = defaultModelIdFuture.thenApplyAsync(llmDomainService::getModelById,
                ragSearchGroupTaskExecutor);
        CompletableFuture<ProviderEntity> originalProviderFuture = modelFuture
                .thenApplyAsync(model -> llmDomainService.getProvider(model.getProviderId()), ragSearchGroupTaskExecutor);

        ContextEntity contextEntity = joinFuture(contextFuture, "加载RAG上下文失败");
        if (contextEntity == null) {
            contextEntity = new ContextEntity();
            contextEntity.setSessionId(sessionId);
        }
        List<MessageEntity> messageHistory = joinFuture(messageHistoryFuture, "加载RAG历史消息失败");
        ModelEntity model = joinFuture(modelFuture, "加载RAG默认模型失败");
        List<String> fallbackChain = joinFuture(fallbackChainFuture, "加载RAG回退模型链失败");
        HighAvailabilityResult result = highAvailabilityDomainService.selectBestProvider(model, userId, sessionId,
                fallbackChain);
        ProviderEntity provider = result.getProvider();
        ModelEntity selectedModel = result.getModel();
        LLMModelConfig llmModelConfig = llmModelConfigFactory.createDefault(selectedModel.getModelId());
        ProviderEntity originalProvider = joinFuture(originalProviderFuture, "加载RAG原始服务商失败");
        AgentEntity ragAgent = createRagAgent();

        RagChatContext ragContext = new RagChatContext();
        ragContext.setSessionId(sessionId);
        ragContext.setUserId(userId);
        ragContext.setUserMessage(ragRequest.getMessage());
        ragContext.setRagSearchRequest(ragRequest.toRagSearchRequest());
        ragContext.setUserRagId(ragRequest.getUserRagId());
        ragContext.setFileId(ragRequest.getFileId());
        ragContext.setAgent(ragAgent);
        ragContext.setOriginalModel(model);
        ragContext.setModel(selectedModel);
        ragContext.setOriginalProvider(originalProvider);
        ragContext.setProvider(provider);
        ragContext.setLlmModelConfig(llmModelConfig);
        ragContext.setInstanceId(result.getInstanceId());
        ragContext.setContextEntity(contextEntity);
        ragContext.setStreaming(true);
        ragContext.setFileUrls(ragRequest.getFileUrls());
        ragContext.setAttachments(ragRequest.getAttachments());

        messageHistory = trimHistoryIfNeededAsync(ragContext, contextEntity, messageHistory);
        ragContext.setMessageHistory(messageHistory);
        return ragContext;
    }

    private List<MessageEntity> trimHistoryIfNeededAsync(RagChatContext ragContext, ContextEntity contextEntity,
            List<MessageEntity> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return messageHistory == null ? new ArrayList<>() : messageHistory;
        }
        CompletableFuture<List<MessageEntity>> trimFuture = supplyAsync(
                () -> historySupportService.trimRagHistoryMessages(ragContext, contextEntity, messageHistory));
        return joinFuture(trimFuture, "裁剪RAG历史消息失败");
    }

    private <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, ragSearchGroupTaskExecutor);
    }

    private <T> T joinFuture(CompletableFuture<T> future, String errorMessage) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new BusinessException(errorMessage + ": " + cause.getMessage(), cause);
        }
    }

    public AgentEntity createRagAgent() {
        AgentEntity ragAgent = new AgentEntity();
        ragAgent.setId(RAG_AGENT_TEMPLATE.getId());
        ragAgent.setUserId(RAG_AGENT_TEMPLATE.getUserId());
        ragAgent.setName(RAG_AGENT_TEMPLATE.getName());
        ragAgent.setSystemPrompt(RAG_AGENT_TEMPLATE.getSystemPrompt());
        ragAgent.setEnabled(RAG_AGENT_TEMPLATE.getEnabled());
        return ragAgent;
    }

    private String resolveRagSessionId(RagStreamChatRequest request, String userId) {
        if (Boolean.TRUE.equals(request.getNewSession())) {
            return ragSessionManager.createNewRagSessionForUser(userId);
        }
        if (StringUtils.hasText(request.getSessionId())) {
            validateRagSessionOwnership(request.getSessionId(), userId);
            ragSessionManager.bindRagSession(userId, request.getSessionId());
            return request.getSessionId();
        }
        return ragSessionManager.createOrGetRagSession(userId);
    }

    private String resolveUserRagSessionId(RagStreamChatRequest request, String userRagId, String userId) {
        if (Boolean.TRUE.equals(request.getNewSession())) {
            return ragSessionManager.createNewUserRagSessionForUser(userId, userRagId);
        }
        if (StringUtils.hasText(request.getSessionId())) {
            validateRagSessionOwnership(request.getSessionId(), userId);
            ragSessionManager.bindUserRagSession(userId, userRagId, request.getSessionId());
            return request.getSessionId();
        }
        return ragSessionManager.createOrGetUserRagSession(userId, userRagId);
    }

    private void validateRagSessionOwnership(String sessionId, String userId) {
        SessionEntity session = sessionDomainService.find(sessionId, userId);
        if (session == null) {
            throw new BusinessException("RAG会话不存在或无权限访问");
        }
        if (!RagSessionManager.RAG_AGENT_ID.equals(session.getAgentId())) {
            throw new BusinessException("指定会话不是RAG临时会话");
        }
    }

    private static AgentEntity createRagAgentTemplate() {
        AgentEntity ragAgent = new AgentEntity();
        ragAgent.setId(RagSessionManager.RAG_AGENT_ID);
        ragAgent.setUserId("system");
        ragAgent.setName("RAG助手");
        ragAgent.setSystemPrompt(RagPromptTemplates.buildRagAgentSystemPrompt());
        ragAgent.setEnabled(true);
        return ragAgent;
    }
}
