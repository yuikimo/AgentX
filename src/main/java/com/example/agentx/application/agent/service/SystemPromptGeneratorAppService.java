package com.example.agentx.application.agent.service;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.agent.service.SystemPromptGeneratorDomainService;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.domain.tool.service.ToolDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.interfaces.dto.agent.request.SystemPromptGenerateRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统提示词生成应用服务
 */
@Service
public class SystemPromptGeneratorAppService {

    private final SystemPromptGeneratorDomainService systemPromptGeneratorDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final LLMDomainService llmDomainService;
    private final ToolDomainService toolDomainService;
    private final LLMServiceFactory llmServiceFactory;

    public SystemPromptGeneratorAppService(SystemPromptGeneratorDomainService systemPromptGeneratorDomainService,
                                           UserSettingsDomainService userSettingsDomainService,
                                           LLMDomainService llmDomainService,
                                           ToolDomainService toolDomainService, LLMServiceFactory llmServiceFactory) {
        this.systemPromptGeneratorDomainService = systemPromptGeneratorDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
        this.toolDomainService = toolDomainService;
        this.llmServiceFactory = llmServiceFactory;
    }

    /**
     * 生成系统提示词
     */
    public String generateSystemPrompt(SystemPromptGenerateRequest request, String userId) {
        // 1. 应用层协调各个领域服务获取数据
        String defaultModelId = userSettingsDomainService.getUserDefaultModelId(userId);
        if (defaultModelId == null) {
            throw new BusinessException("未设置默认模型");
        }

        // 2. 获取模型和提供商信息
        ModelEntity model = llmDomainService.getModelById(defaultModelId);
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
}