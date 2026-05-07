package com.example.agentx.application.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.dto.AgentPreviewRequest;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.preview.PreviewMessageHandler;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.transport.MessageTransport;
import com.example.agentx.infrastructure.transport.MessageTransportFactory;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PreviewConversationAppService {
    private final MessageTransportFactory transportFactory;
    private final PreviewMessageHandler previewMessageHandler;
    private final LLMDomainService llmDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final LLMModelConfigFactory llmModelConfigFactory;
    private final ConversationEnvironmentSupportService environmentSupportService;
    private final ConversationHistorySupportService historySupportService;

    public PreviewConversationAppService(MessageTransportFactory transportFactory,
            PreviewMessageHandler previewMessageHandler, LLMDomainService llmDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMModelConfigFactory llmModelConfigFactory,
            ConversationEnvironmentSupportService environmentSupportService,
            ConversationHistorySupportService historySupportService) {
        this.transportFactory = transportFactory;
        this.previewMessageHandler = previewMessageHandler;
        this.llmDomainService = llmDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmModelConfigFactory = llmModelConfigFactory;
        this.environmentSupportService = environmentSupportService;
        this.historySupportService = historySupportService;
    }

    public SseEmitter previewAgent(AgentPreviewRequest previewRequest, String userId) {
        ChatContext environment = preparePreviewEnvironment(previewRequest, userId);
        MessageTransport<SseEmitter> transport = transportFactory
                .getTransport(MessageTransportFactory.TRANSPORT_TYPE_SSE);
        return previewMessageHandler.chat(environment, transport);
    }

    private ChatContext preparePreviewEnvironment(AgentPreviewRequest previewRequest, String userId) {
        AgentEntity virtualAgent = createVirtualAgent(previewRequest, userId);
        String modelId = getPreviewModelId(previewRequest, userId);
        ModelEntity model = environmentSupportService.getModelForChat(null, modelId, userId);

        ProviderEntity provider = llmDomainService.getProvider(model.getProviderId());
        provider.isActive();
        provider.isAvailable(provider.getUserId());

        List<String> mcpServerNames = environmentSupportService.getMcpServerNames(previewRequest.getToolIds(), userId);
        LLMModelConfig llmModelConfig = llmModelConfigFactory.createDefault(modelId);

        ChatContext chatContext = new ChatContext();
        chatContext.setSessionId("preview-session");
        chatContext.setUserId(userId);
        chatContext.setUserMessage(previewRequest.getUserMessage());
        chatContext.setAgent(virtualAgent);
        chatContext.setModel(model);
        chatContext.setProvider(provider);
        chatContext.setLlmModelConfig(llmModelConfig);
        chatContext.setMcpServerNames(mcpServerNames);
        chatContext.setFileUrls(previewRequest.getFileUrls());
        chatContext.setAttachments(previewRequest.getAttachments());

        historySupportService.setupPreviewContextAndHistory(chatContext, previewRequest);
        return chatContext;
    }

    private String getPreviewModelId(AgentPreviewRequest previewRequest, String userId) {
        String modelId = previewRequest.getModelId();
        if (modelId == null || modelId.trim().isEmpty()) {
            modelId = userSettingsDomainService.getUserDefaultModelId(userId);
            if (modelId == null) {
                throw new BusinessException("用户未设置默认模型，且预览请求中未指定模型");
            }
        }
        return modelId;
    }

    private AgentEntity createVirtualAgent(AgentPreviewRequest previewRequest, String userId) {
        AgentEntity virtualAgent = new AgentEntity();
        virtualAgent.setId("preview-agent");
        virtualAgent.setUserId(userId);
        virtualAgent.setName("预览助理");
        virtualAgent.setSystemPrompt(previewRequest.getSystemPrompt());
        virtualAgent.setToolIds(previewRequest.getToolIds());
        virtualAgent.setToolPresetParams(previewRequest.getToolPresetParams());
        virtualAgent.setKnowledgeBaseIds(previewRequest.getKnowledgeBaseIds());
        virtualAgent.setMultiModal(Boolean.TRUE.equals(previewRequest.getMultiModal()));
        virtualAgent.setEnabled(true);
        virtualAgent.setCreatedAt(LocalDateTime.now());
        virtualAgent.setUpdatedAt(LocalDateTime.now());
        return virtualAgent;
    }
}
