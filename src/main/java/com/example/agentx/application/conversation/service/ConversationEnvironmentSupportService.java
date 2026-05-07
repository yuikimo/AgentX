package com.example.agentx.application.conversation.service;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.application.conversation.dto.ChatRequest;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.llm.service.UserModelBindingService;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.AgentVersionEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.agent.service.AgentDomainService;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.tool.model.UserToolEntity;
import com.example.agentx.domain.tool.service.UserToolDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationEnvironmentSupportService {
    private final AgentDomainService agentDomainService;
    private final LLMDomainService llmDomainService;
    private final UserToolDomainService userToolDomainService;
    private final UserModelBindingService userModelBindingService;

    public ConversationEnvironmentSupportService(AgentDomainService agentDomainService,
            LLMDomainService llmDomainService, UserToolDomainService userToolDomainService,
            UserModelBindingService userModelBindingService) {
        this.agentDomainService = agentDomainService;
        this.llmDomainService = llmDomainService;
        this.userToolDomainService = userToolDomainService;
        this.userModelBindingService = userModelBindingService;
    }

    public AgentEntity getAgentWithValidation(String agentId, String userId) {
        AgentEntity baseAgent = agentDomainService.getAgentById(agentId);
        AgentEntity agent = new AgentEntity();
        BeanUtils.copyProperties(baseAgent, agent);
        if (!agent.getUserId().equals(userId) && !agent.getEnabled()) {
            throw new BusinessException("agent已被禁用");
        }

        if (!agent.getUserId().equals(userId)) {
            AgentVersionEntity latestAgentVersion = agentDomainService.getLatestAgentVersion(agentId);
            BeanUtils.copyProperties(latestAgentVersion, agent);
        }

        return agent;
    }

    public List<String> getMcpServerNames(List<String> toolIds, String userId) {
        if (toolIds == null || toolIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<UserToolEntity> installTool = userToolDomainService.getInstallTool(toolIds, userId);
        return installTool.stream().map(UserToolEntity::getMcpServerName)
                .filter(serverName -> serverName != null && !serverName.isBlank()).distinct()
                .collect(Collectors.toList());
    }

    public ModelEntity getModelForChat(LLMModelConfig llmModelConfig, String specifiedModelId, String userId) {
        String finalModelId;
        if (StringUtils.hasText(specifiedModelId)) {
            finalModelId = specifiedModelId;
        } else {
            finalModelId = llmModelConfig != null ? llmModelConfig.getModelId() : null;
        }

        if (!StringUtils.hasText(finalModelId)) {
            finalModelId = userModelBindingService.resolveAndEnsureDefaultChatModelId(userId);
        }

        if (!StringUtils.hasText(finalModelId)) {
            throw new BusinessException("未找到可用聊天模型，请先在通用设置中配置可用模型");
        }

        ModelEntity model = llmDomainService.getModelById(finalModelId);
        if (!model.isChatType()) {
            throw new BusinessException("当前模型不是聊天模型，请重新选择");
        }
        model.isActive();
        return model;
    }

    public ChatContext createChatContext(ChatRequest chatRequest, String userId, AgentEntity agent,
            ModelEntity originalModel, ModelEntity selectedModel, ProviderEntity originalProvider,
            ProviderEntity provider, LLMModelConfig llmModelConfig, List<String> mcpServerNames, String instanceId) {
        ChatContext chatContext = new ChatContext();
        chatContext.setSessionId(chatRequest.getSessionId());
        chatContext.setUserId(userId);
        chatContext.setUserMessage(chatRequest.getMessage());
        chatContext.setAgent(agent);
        chatContext.setOriginalModel(originalModel);
        chatContext.setModel(selectedModel);
        chatContext.setOriginalProvider(originalProvider);
        chatContext.setProvider(provider);
        chatContext.setLlmModelConfig(llmModelConfig);
        chatContext.setMcpServerNames(mcpServerNames);
        chatContext.setFileUrls(chatRequest.getFileUrls());
        chatContext.setAttachments(chatRequest.getAttachments());
        chatContext.setInstanceId(instanceId);
        return chatContext;
    }

    public ChatContext createWidgetChatContext(String sessionId, String userId, String userMessage, AgentEntity agent,
            ModelEntity model, ProviderEntity provider, LLMModelConfig llmModelConfig, List<String> mcpServerNames,
            String instanceId, String publicId, List<String> fileUrls, List<ConversationAttachment> attachments) {
        ChatContext chatContext = new ChatContext();
        chatContext.setSessionId(sessionId);
        chatContext.setUserId(userId);
        chatContext.setUserMessage(userMessage);
        chatContext.setAgent(agent);
        chatContext.setModel(model);
        chatContext.setProvider(provider);
        chatContext.setLlmModelConfig(llmModelConfig);
        chatContext.setMcpServerNames(mcpServerNames);
        chatContext.setFileUrls(fileUrls);
        chatContext.setAttachments(attachments);
        chatContext.setInstanceId(instanceId);
        chatContext.setPublicAccess(true);
        chatContext.setPublicId(publicId);
        return chatContext;
    }
}
