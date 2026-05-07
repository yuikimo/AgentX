package com.example.agentx.application.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.agentx.application.agent.assembler.AgentAssembler;
import com.example.agentx.application.agent.assembler.AgentWorkspaceAssembler;
import com.example.agentx.application.agent.dto.AgentDTO;
import com.example.agentx.application.llm.service.UserModelBindingService;
import com.example.agentx.domain.agent.constant.PublishStatus;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.agent.model.AgentVersionEntity;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.agent.service.AgentDomainService;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.ConversationDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.interfaces.dto.agent.request.UpdateModelConfigRequest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Agent应用服务，用于适配领域层的Agent服务 职责： 1. 接收和验证来自接口层的请求 2. 将请求转换为领域对象或参数 3. 调用领域服务执行业务逻辑 4. 转换和返回结果给接口层 */
@Service
public class AgentWorkspaceAppService {

    private final AgentWorkspaceDomainService agentWorkspaceDomainService;

    private final AgentDomainService agentServiceDomainService;

    private final SessionDomainService sessionDomainService;

    private final ConversationDomainService conversationDomainService;
    private final LLMDomainService llmDomainService;
    private final UserModelBindingService userModelBindingService;

    public AgentWorkspaceAppService(AgentWorkspaceDomainService agentWorkspaceDomainService,
            AgentDomainService agentServiceDomainService, SessionDomainService sessionDomainService,
            ConversationDomainService conversationDomainService, LLMDomainService llmDomainService,
            UserModelBindingService userModelBindingService) {
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.agentServiceDomainService = agentServiceDomainService;
        this.sessionDomainService = sessionDomainService;
        this.conversationDomainService = conversationDomainService;
        this.llmDomainService = llmDomainService;
        this.userModelBindingService = userModelBindingService;
    }

    /** 获取工作区下的助理
     * 
     * @param userId 用户id
     * @return AgentDTO */
    public List<AgentDTO> getAgents(String userId) {
        List<AgentEntity> workspaceAgents = agentWorkspaceDomainService.getWorkspaceAgents(userId);
        List<AgentDTO> agentDTOs = AgentAssembler.toDTOs(workspaceAgents);
        if (workspaceAgents.isEmpty()) {
            return agentDTOs;
        }

        List<String> agentIds = workspaceAgents.stream().map(AgentEntity::getId).collect(Collectors.toList());
        Map<String, AgentWorkspaceEntity> workspaceMap = agentWorkspaceDomainService.listAgents(agentIds, userId)
                .stream().collect(Collectors.toMap(AgentWorkspaceEntity::getAgentId, workspace -> workspace));

        Set<String> requiredModelIds = new HashSet<>();
        Map<String, String> resolvedWorkspaceModelIdMap = new HashMap<>();
        workspaceMap.values().forEach(workspace -> {
            String modelId = ensureWorkspaceBoundModel(workspace, userId);
            if (StringUtils.hasText(modelId)) {
                resolvedWorkspaceModelIdMap.put(workspace.getAgentId(), modelId);
                requiredModelIds.add(modelId);
            }
        });

        Map<String, ModelEntity> modelMap = new HashMap<>();
        if (!requiredModelIds.isEmpty()) {
            modelMap = llmDomainService.getModelsByIds(requiredModelIds).stream()
                    .collect(Collectors.toMap(ModelEntity::getId, model -> model));
        }

        for (AgentDTO agentDTO : agentDTOs) {
            String resolvedModelId = resolvedWorkspaceModelIdMap.get(agentDTO.getId());
            if (!StringUtils.hasText(resolvedModelId)) {
                continue;
            }

            agentDTO.setModelId(resolvedModelId);
            agentDTO.setModelSource("BOUND");

            ModelEntity modelEntity = modelMap.get(resolvedModelId);
            if (modelEntity != null) {
                agentDTO.setModelName(modelEntity.getName());
            }
        }

        return agentDTOs;
    }

    /** 删除工作区中的助理
     * @param agentId 助理id
     * @param userId 用户id */
    @Transactional
    public void deleteAgent(String agentId, String userId) {

        // agent如果是自己的则不允许删除
        AgentEntity agent = agentServiceDomainService.getAgentById(agentId);
        if (agent.getUserId().equals(userId)) {
            throw new BusinessException("该助理属于自己，不允许删除");
        }

        boolean deleteAgent = agentWorkspaceDomainService.deleteAgent(agentId, userId);
        if (!deleteAgent) {
            throw new BusinessException("删除助理失败");
        }
        List<String> sessionIds = sessionDomainService.getSessionsByAgentId(agentId, userId).stream()
                .map(SessionEntity::getId).collect(Collectors.toList());
        if (sessionIds.isEmpty()) {
            return;
        }
        sessionDomainService.deleteSessions(sessionIds);
        conversationDomainService.deleteConversationMessages(sessionIds);
    }

    public LLMModelConfig getConfiguredModelId(String agentId, String userId) {
        AgentWorkspaceEntity workspace = agentWorkspaceDomainService.getWorkspace(agentId, userId);
        String resolvedModelId = ensureWorkspaceBoundModel(workspace, userId);
        if (StringUtils.hasText(resolvedModelId)) {
            workspace.getLlmModelConfig().setModelId(resolvedModelId);
        }
        return workspace.getLlmModelConfig();
    }

    /** 保存agent的模型配置
     * @param agentId agent ID
     * @param userId 用户ID
     * @param request 模型配置 */
    public void updateModelConfig(String agentId, String userId, UpdateModelConfigRequest request) {
        LLMModelConfig llmModelConfig = AgentWorkspaceAssembler.toLLMModelConfig(request);
        String modelId = llmModelConfig.getModelId();

        // 激活校验
        ModelEntity model = llmDomainService.getModelById(modelId);
        model.isActive();
        ProviderEntity provider = llmDomainService.getProvider(model.getProviderId());
        provider.isActive();
        agentWorkspaceDomainService.update(new AgentWorkspaceEntity(agentId, userId, llmModelConfig));
    }

    // 添加到工作区
    public void addAgent(String agentId, String userId) {
        AgentEntity agent = agentServiceDomainService.getAgentById(agentId);
        if (agent.getUserId().equals(userId)) {
            throw new BusinessException("不可添加自己的助理");
        }
        if (agentWorkspaceDomainService.exist(agentId, userId)) {
            throw new BusinessException("不可重复添加助理");
        }

        agent.isEnable();
        String publishedVersion = agent.getPublishedVersion();
        AgentVersionEntity agentVersionEntity = agentServiceDomainService.getAgentVersionById(publishedVersion);
        if (!agentVersionEntity.getPublishStatusEnum().equals(PublishStatus.PUBLISHED)) {
            throw new BusinessException("助理未发布");
        }

        LLMModelConfig llmModelConfig = new LLMModelConfig();
        llmModelConfig.setModelId(userModelBindingService.resolveAndEnsureDefaultChatModelId(userId));
        agentWorkspaceDomainService.save(new AgentWorkspaceEntity(agentId, userId, llmModelConfig));
    }

    private String ensureWorkspaceBoundModel(AgentWorkspaceEntity workspace, String userId) {
        LLMModelConfig llmModelConfig = workspace.getLlmModelConfig();
        String boundModelId = llmModelConfig.getModelId();

        boolean modelValid = false;
        if (StringUtils.hasText(boundModelId)) {
            ModelEntity model = llmDomainService.findModelById(boundModelId);
            modelValid = model != null && Boolean.TRUE.equals(model.getStatus()) && model.isChatType();
        }

        if (modelValid) {
            return boundModelId;
        }

        String replacementModelId = userModelBindingService.resolveAndEnsureDefaultChatModelId(userId);
        if (!StringUtils.hasText(replacementModelId)) {
            return null;
        }
        if (replacementModelId.equals(boundModelId)) {
            return replacementModelId;
        }

        llmModelConfig.setModelId(replacementModelId);
        agentWorkspaceDomainService.update(new AgentWorkspaceEntity(workspace.getAgentId(), userId, llmModelConfig));
        return replacementModelId;
    }
}
