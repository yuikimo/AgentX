package com.example.agentx.domain.agent.service;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LlmDomainService;
import com.example.agentx.infrastructure.llm.LLMProviderService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;

/**
 * 模型提供商门面
 * 负责获取和验证模型及提供商
 */
@Service
public class ModelProviderFacade {

    private final AgentWorkspaceDomainService agentWorkspaceDomainService;
    private final LlmDomainService llmDomainService;

    public ModelProviderFacade(
            AgentWorkspaceDomainService agentWorkspaceDomainService,
            LlmDomainService llmDomainService) {
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.llmDomainService = llmDomainService;
    }

    /**
     * 获取并验证模型和提供商
     *
     * @param agentId 代理ID
     * @param userId  用户ID
     * @return 模型提供商结果
     */
    public ModelProviderResult getModelAndProvider(String agentId, String userId) {
        // 从工作区中获取对应的模型信息
        AgentWorkspaceEntity workspace = agentWorkspaceDomainService.getWorkspace(agentId, userId);
        LLMModelConfig llmModelConfig = workspace.getLlmModelConfig();
        String modelId = llmModelConfig.getModelId();
        ModelEntity model = llmDomainService.getModelById(modelId);
        // 验证模型是否激活
        model.isActive();

        // 获取并验证服务商信息
        ProviderEntity provider = llmDomainService.getProvider(model.getProviderId(), userId);
        provider.isActive();

        // 构建提供商配置
        com.example.agentx.domain.llm.model.config.ProviderConfig domainConfig = provider.getConfig();
        ProviderConfig providerConfig = new ProviderConfig(
                domainConfig.getApiKey(),
                domainConfig.getBaseUrl(),
                model.getModelId(),
                provider.getProtocol());

        // 获取流式聊天客户端
        StreamingChatLanguageModel chatStreamClient = LLMProviderService.getStream(
                provider.getProtocol(),
                providerConfig);

        return new ModelProviderResult(model, provider, llmModelConfig, providerConfig, chatStreamClient);
    }

    /**
     * 模型提供商结果
     */
    public record ModelProviderResult(ModelEntity modelEntity, ProviderEntity providerEntity,
                                      LLMModelConfig llmModelConfig, ProviderConfig providerConfig,
                                      StreamingChatLanguageModel chatStreamClient) {
    }
}
