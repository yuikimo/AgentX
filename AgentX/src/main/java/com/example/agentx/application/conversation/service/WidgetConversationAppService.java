package com.example.agentx.application.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.dto.ChatResponse;
import com.example.agentx.application.conversation.service.handler.MessageHandlerFactory;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.AbstractMessageHandler;
import com.example.agentx.application.conversation.service.message.rag.RagChatContext;
import com.example.agentx.application.rag.dto.RagSearchRequest;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.AgentWidgetEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.llm.model.HighAvailabilityResult;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.transport.MessageTransport;
import com.example.agentx.infrastructure.transport.MessageTransportFactory;
import com.example.agentx.interfaces.dto.agent.request.WidgetChatRequest;

import java.util.List;

@Service
public class WidgetConversationAppService {
    private final MessageHandlerFactory messageHandlerFactory;
    private final MessageTransportFactory transportFactory;
    private final LLMDomainService llmDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final HighAvailabilityDomainService highAvailabilityDomainService;
    private final LLMModelConfigFactory llmModelConfigFactory;
    private final ConversationEnvironmentSupportService environmentSupportService;
    private final ConversationHistorySupportService historySupportService;
    private final RagConversationAppService ragConversationAppService;

    public WidgetConversationAppService(MessageHandlerFactory messageHandlerFactory,
            MessageTransportFactory transportFactory, LLMDomainService llmDomainService,
            UserSettingsDomainService userSettingsDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService,
            LLMModelConfigFactory llmModelConfigFactory,
            ConversationEnvironmentSupportService environmentSupportService,
            ConversationHistorySupportService historySupportService,
            RagConversationAppService ragConversationAppService) {
        this.messageHandlerFactory = messageHandlerFactory;
        this.transportFactory = transportFactory;
        this.llmDomainService = llmDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.llmModelConfigFactory = llmModelConfigFactory;
        this.environmentSupportService = environmentSupportService;
        this.historySupportService = historySupportService;
        this.ragConversationAppService = ragConversationAppService;
    }

    public SseEmitter widgetChat(String publicId, WidgetChatRequest widgetChatRequest, AgentWidgetEntity widgetEntity) {
        ChatContext environment = prepareWidgetEnvironment(publicId, widgetChatRequest, widgetEntity);
        MessageTransport<SseEmitter> transport = transportFactory
                .getTransport(MessageTransportFactory.TRANSPORT_TYPE_SSE);
        AbstractMessageHandler handler = messageHandlerFactory.getHandler(widgetEntity);
        return handler.chat(environment, transport);
    }

    public ChatResponse widgetChatSync(String publicId, WidgetChatRequest widgetChatRequest,
            AgentWidgetEntity widgetEntity) {
        ChatContext environment = prepareWidgetEnvironment(publicId, widgetChatRequest, widgetEntity);
        environment.setStreaming(false);
        MessageTransport<ChatResponse> transport = transportFactory
                .getTransport(MessageTransportFactory.TRANSPORT_TYPE_SYNC);
        AbstractMessageHandler handler = messageHandlerFactory.getHandler(widgetEntity);
        return handler.chat(environment, transport);
    }

    private ChatContext prepareWidgetEnvironment(String publicId, WidgetChatRequest widgetChatRequest,
            AgentWidgetEntity widgetEntity) {
        if (widgetEntity.isRagWidget()) {
            return createRagWidgetContext(publicId, widgetChatRequest, widgetEntity);
        }

        String agentId = widgetEntity.getAgentId();
        String creatorUserId = widgetEntity.getUserId();
        String sessionId = widgetChatRequest.getSessionId();

        AgentEntity agent = environmentSupportService.getAgentWithValidation(agentId, creatorUserId);
        ModelEntity model = llmDomainService.getModelById(widgetEntity.getModelId());
        List<String> mcpServerNames = environmentSupportService.getMcpServerNames(agent.getToolIds(), creatorUserId);

        List<String> fallbackChain = userSettingsDomainService.getUserFallbackChain(creatorUserId);
        HighAvailabilityResult result = highAvailabilityDomainService.selectBestProvider(model, creatorUserId,
                sessionId, fallbackChain);
        ProviderEntity provider = result.getProvider();
        ModelEntity selectedModel = result.getModel();
        String instanceId = result.getInstanceId();
        provider.isActive();

        LLMModelConfig llmModelConfig = llmModelConfigFactory.resolveForWidget(agentId, creatorUserId,
                selectedModel.getModelId());

        ChatContext chatContext = environmentSupportService.createWidgetChatContext(widgetChatRequest.getSessionId(),
                creatorUserId, widgetChatRequest.getMessage(), agent, selectedModel, provider, llmModelConfig,
                mcpServerNames, instanceId, publicId, widgetChatRequest.getFileUrls(), widgetChatRequest.getAttachments());
        historySupportService.setupWidgetContextAndHistory(chatContext);
        return chatContext;
    }

    private ChatContext createRagWidgetContext(String publicId, WidgetChatRequest widgetChatRequest,
            AgentWidgetEntity widgetEntity) {
        String creatorUserId = widgetEntity.getUserId();
        String sessionId = widgetChatRequest.getSessionId();

        AgentEntity ragAgent = ragConversationAppService.createRagAgent();
        ModelEntity model = llmDomainService.getModelById(widgetEntity.getModelId());

        List<String> fallbackChain = userSettingsDomainService.getUserFallbackChain(creatorUserId);
        HighAvailabilityResult result = highAvailabilityDomainService.selectBestProvider(model, creatorUserId,
                sessionId, fallbackChain);
        ProviderEntity provider = result.getProvider();
        ModelEntity selectedModel = result.getModel();
        String instanceId = result.getInstanceId();
        provider.isActive();

        LLMModelConfig llmModelConfig = llmModelConfigFactory.createDefault(selectedModel.getModelId());

        RagSearchRequest ragSearchRequest = new RagSearchRequest();
        ragSearchRequest.setQuestion(widgetChatRequest.getMessage());
        ragSearchRequest.setDatasetIds(widgetEntity.getKnowledgeBaseIds());
        ragSearchRequest.setMaxResults(5);
        ragSearchRequest.setMinScore(0.7);
        ragSearchRequest.setEnableRerank(true);

        RagChatContext ragContext = new RagChatContext();
        ragContext.setSessionId(sessionId);
        ragContext.setUserId(creatorUserId);
        ragContext.setUserMessage(widgetChatRequest.getMessage());
        ragContext.setAgent(ragAgent);
        ragContext.setModel(selectedModel);
        ragContext.setProvider(provider);
        ragContext.setLlmModelConfig(llmModelConfig);
        ragContext.setInstanceId(instanceId);
        ragContext.setRagSearchRequest(ragSearchRequest);
        ragContext.setUserRagId(null);
        ragContext.setFileUrls(widgetChatRequest.getFileUrls());
        ragContext.setAttachments(widgetChatRequest.getAttachments());
        ragContext.setPublicAccess(true);
        ragContext.setPublicId(publicId);

        historySupportService.setupWidgetContextAndHistory(ragContext);
        return ragContext;
    }
}
