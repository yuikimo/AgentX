package com.example.agentx.application.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.dto.ChatRequest;
import com.example.agentx.application.conversation.dto.ChatResponse;
import com.example.agentx.application.conversation.service.handler.MessageHandlerFactory;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.AbstractMessageHandler;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.model.HighAvailabilityResult;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.transport.MessageTransport;
import com.example.agentx.infrastructure.transport.MessageTransportFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class StandardConversationAppService {
    private final SessionDomainService sessionDomainService;
    private final AgentWorkspaceDomainService agentWorkspaceDomainService;
    private final LLMDomainService llmDomainService;
    private final MessageHandlerFactory messageHandlerFactory;
    private final MessageTransportFactory transportFactory;
    private final UserSettingsDomainService userSettingsDomainService;
    private final HighAvailabilityDomainService highAvailabilityDomainService;
    private final LLMModelConfigFactory llmModelConfigFactory;
    private final ConversationEnvironmentSupportService environmentSupportService;
    private final ConversationHistorySupportService historySupportService;

    public StandardConversationAppService(SessionDomainService sessionDomainService,
            AgentWorkspaceDomainService agentWorkspaceDomainService, LLMDomainService llmDomainService,
            MessageHandlerFactory messageHandlerFactory, MessageTransportFactory transportFactory,
            UserSettingsDomainService userSettingsDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService,
            LLMModelConfigFactory llmModelConfigFactory,
            ConversationEnvironmentSupportService environmentSupportService,
            ConversationHistorySupportService historySupportService) {
        this.sessionDomainService = sessionDomainService;
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.llmDomainService = llmDomainService;
        this.messageHandlerFactory = messageHandlerFactory;
        this.transportFactory = transportFactory;
        this.userSettingsDomainService = userSettingsDomainService;
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.llmModelConfigFactory = llmModelConfigFactory;
        this.environmentSupportService = environmentSupportService;
        this.historySupportService = historySupportService;
    }

    public SseEmitter chat(ChatRequest chatRequest, String userId) {
        ChatContext environment = prepareEnvironmentWithModel(chatRequest, userId, null);
        return chat(environment, chatRequest);
    }

    public SseEmitter chat(ChatContext environment, ChatRequest chatRequest) {
        MessageTransport<SseEmitter> transport = transportFactory
                .getTransport(MessageTransportFactory.TRANSPORT_TYPE_SSE);
        AbstractMessageHandler handler = messageHandlerFactory.getHandler(chatRequest);
        return handler.chat(environment, transport);
    }

    public SseEmitter chatWithModel(ChatRequest chatRequest, String userId, String modelId) {
        ChatContext environment = prepareEnvironmentWithModel(chatRequest, userId, modelId);
        MessageTransport<SseEmitter> transport = transportFactory
                .getTransport(MessageTransportFactory.TRANSPORT_TYPE_SSE);
        AbstractMessageHandler handler = messageHandlerFactory.getHandler(chatRequest);
        return handler.chat(environment, transport);
    }

    public ChatResponse chatSyncWithModel(ChatRequest chatRequest, String userId, String modelId) {
        ChatContext environment = prepareEnvironmentWithModel(chatRequest, userId, modelId);
        environment.setStreaming(false);
        MessageTransport<ChatResponse> transport = transportFactory
                .getTransport(MessageTransportFactory.TRANSPORT_TYPE_SYNC);
        AbstractMessageHandler handler = messageHandlerFactory.getHandler(chatRequest);
        return handler.chat(environment, transport);
    }

    public ChatContext prepareEnvironmentWithModel(ChatRequest chatRequest, String userId, String modelId) {
        String sessionId = chatRequest.getSessionId();
        SessionEntity session = sessionDomainService.getSession(sessionId, userId);
        String agentId = session.getAgentId();
        AgentEntity agent = environmentSupportService.getAgentWithValidation(agentId, userId);

        CompletableFuture<List<String>> mcpServerNamesFuture = CompletableFuture
                .supplyAsync(() -> environmentSupportService.getMcpServerNames(agent.getToolIds(), userId));
        CompletableFuture<AgentWorkspaceEntity> workspaceFuture = CompletableFuture
                .supplyAsync(() -> agentWorkspaceDomainService.getWorkspace(agentId, userId));
        CompletableFuture<List<String>> fallbackChainFuture = CompletableFuture
                .supplyAsync(() -> userSettingsDomainService.getUserFallbackChain(userId));

        AgentWorkspaceEntity workspace = joinFuture(workspaceFuture, "加载工作区失败");
        LLMModelConfig llmModelConfig = llmModelConfigFactory.resolveForChat(workspace.getLlmModelConfig(), userId,
                modelId);
        ModelEntity model = environmentSupportService.getModelForChat(llmModelConfig, modelId, userId);

        List<String> fallbackChain = joinFuture(fallbackChainFuture, "加载回退模型链失败");
        HighAvailabilityResult result = highAvailabilityDomainService.selectBestProvider(model, userId, sessionId,
                fallbackChain);
        ProviderEntity originalProvider = llmDomainService.getProvider(model.getProviderId());
        ProviderEntity provider = result.getProvider();
        ModelEntity selectedModel = result.getModel();
        String instanceId = result.getInstanceId();
        provider.isActive();
        List<String> mcpServerNames = joinFuture(mcpServerNamesFuture, "加载工具配置失败");

        ChatContext chatContext = environmentSupportService.createChatContext(chatRequest, userId, agent, model,
                selectedModel, originalProvider, provider, llmModelConfig, mcpServerNames, instanceId);
        historySupportService.setupContextAndHistory(chatContext);
        return chatContext;
    }

    private <T> T joinFuture(CompletableFuture<T> future, String errorMessage) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new BusinessException(errorMessage + ": " + cause.getMessage(), cause);
        }
    }
}
