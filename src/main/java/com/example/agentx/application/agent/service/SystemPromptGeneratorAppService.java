package com.example.agentx.application.agent.service;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.application.llm.service.UserModelBindingService;
import com.example.agentx.domain.agent.service.SystemPromptGeneratorDomainService;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.domain.tool.service.ToolDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.interfaces.dto.agent.request.SystemPromptGenerateRequest;

import java.util.ArrayList;
import java.util.List;

/** 系统提示词生成应用服务 */
@Service
public class SystemPromptGeneratorAppService {

    private final SystemPromptGeneratorDomainService systemPromptGeneratorDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final LLMDomainService llmDomainService;
    private final ToolDomainService toolDomainService;
    private final LLMServiceFactory llmServiceFactory;
    private final SessionDomainService sessionDomainService;
    private final AgentWorkspaceDomainService agentWorkspaceDomainService;
    private final UserModelBindingService userModelBindingService;

    public SystemPromptGeneratorAppService(SystemPromptGeneratorDomainService systemPromptGeneratorDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            ToolDomainService toolDomainService, LLMServiceFactory llmServiceFactory, SessionDomainService sessionDomainService,
            AgentWorkspaceDomainService agentWorkspaceDomainService, UserModelBindingService userModelBindingService) {
        this.systemPromptGeneratorDomainService = systemPromptGeneratorDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
        this.toolDomainService = toolDomainService;
        this.llmServiceFactory = llmServiceFactory;
        this.sessionDomainService = sessionDomainService;
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.userModelBindingService = userModelBindingService;
    }

    /** 生成系统提示词 */
    public String generateSystemPrompt(SystemPromptGenerateRequest request, String userId) {
        // 1. 解析用于生成的模型（与普通对话一致：指定模型 > 会话绑定workspace模型 > Agent workspace模型 > 用户默认聊天模型）
        String finalModelId = resolveModelIdForGeneration(request, userId);
        if (!StringUtils.hasText(finalModelId)) {
            throw new BusinessException("未找到可用聊天模型，请先在通用设置中配置可用模型");
        }

        // 2. 获取模型和提供商信息
        ModelEntity model = llmDomainService.getModelById(finalModelId);
        if (!model.isChatType()) {
            throw new BusinessException("当前模型不是聊天模型，请重新选择");
        }
        model.isActive();
        ProviderEntity provider = llmDomainService.getProvider(model.getProviderId());

        // 3. 获取工具详细信息
        List<ToolEntity> tools = new ArrayList<>();
        if (request.getToolIds() != null && !request.getToolIds().isEmpty()) {
            tools = toolDomainService.getByIds(request.getToolIds());
        }

        // 4. 创建LLM客户端
        ChatModel chatModel = llmServiceFactory.getStrandClient(provider, model);

        // 5. 调用系统提示词生成领域服务（只负责核心生成逻辑）
        return systemPromptGeneratorDomainService.generateSystemPrompt(request.getAgentName(),
                request.getAgentDescription(), tools, chatModel);
    }

    private String resolveModelIdForGeneration(SystemPromptGenerateRequest request, String userId) {
        if (request != null && StringUtils.hasText(request.getModelId())) {
            return request.getModelId();
        }

        String agentId = null;

        if (request != null && StringUtils.hasText(request.getSessionId())) {
            try {
                SessionEntity session = sessionDomainService.find(request.getSessionId(), userId);
                if (session != null && StringUtils.hasText(session.getAgentId())) {
                    agentId = session.getAgentId();
                }
            } catch (Exception ignore) {
                // Optional hint only; fall through to other resolution paths.
            }
        }

        if (!StringUtils.hasText(agentId) && request != null && StringUtils.hasText(request.getAgentId())) {
            agentId = request.getAgentId();
        }

        // Try workspace-bound model
        if (StringUtils.hasText(agentId)) {
            try {
                AgentWorkspaceEntity workspace = agentWorkspaceDomainService.findWorkspace(agentId, userId);
                if (workspace != null && workspace.getLlmModelConfig() != null
                        && StringUtils.hasText(workspace.getLlmModelConfig().getModelId())) {
                    String workspaceModelId = workspace.getLlmModelConfig().getModelId();
                    ModelEntity workspaceModel = llmDomainService.findModelById(workspaceModelId);
                    if (workspaceModel != null && Boolean.TRUE.equals(workspaceModel.getStatus())
                            && workspaceModel.isChatType()) {
                        return workspaceModelId;
                    }
                }
            } catch (Exception ignore) {
                // Fall back to default model.
            }
        }

        // Default chat model (ensure exists)
        String defaultModelId = userModelBindingService.resolveAndEnsureDefaultChatModelId(userId);
        if (!StringUtils.hasText(defaultModelId)) {
            // Backward compatibility: keep old error message when default is not set.
            String legacyDefault = userSettingsDomainService.getUserDefaultModelId(userId);
            if (!StringUtils.hasText(legacyDefault)) {
                throw new BusinessException("未设置默认模型");
            }
            return legacyDefault;
        }
        return defaultModelId;
    }
}
