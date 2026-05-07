package com.example.agentx.application.conversation.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.example.agentx.application.llm.service.UserModelBindingService;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.shared.enums.TokenOverflowStrategyEnum;

/** 统一管理对话模型配置的复制、默认值补齐与兜底解析 */
@Component
public class LLMModelConfigFactory {

    private static final int DEFAULT_MAX_TOKENS = 4000;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final double DEFAULT_TOP_P = 0.9;
    private static final double DEFAULT_RESERVE_RATIO = 0.25;
    private static final int DEFAULT_SUMMARY_THRESHOLD = 35;
    private static final int MIN_SUMMARY_THRESHOLD = 30;
    private static final int MAX_SUMMARY_THRESHOLD = 90;

    private final LLMDomainService llmDomainService;
    private final UserModelBindingService userModelBindingService;
    private final AgentWorkspaceDomainService agentWorkspaceDomainService;

    public LLMModelConfigFactory(LLMDomainService llmDomainService,
            UserModelBindingService userModelBindingService,
            AgentWorkspaceDomainService agentWorkspaceDomainService) {
        this.llmDomainService = llmDomainService;
        this.userModelBindingService = userModelBindingService;
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
    }

    public LLMModelConfig resolveForChat(LLMModelConfig configuredModelConfig, String userId, String specifiedModelId) {
        LLMModelConfig resolved = copyOf(configuredModelConfig);
        if (resolved == null) {
            resolved = new LLMModelConfig();
        }
        if (StringUtils.hasText(specifiedModelId)) {
            resolved.setModelId(specifiedModelId);
            return applyDefaults(resolved, specifiedModelId);
        }

        String resolvedModelId = resolved.getModelId();
        if (!StringUtils.hasText(resolvedModelId) || !isActiveChatModel(resolvedModelId)) {
            String defaultModelId = userModelBindingService.resolveAndEnsureDefaultChatModelId(userId);
            if (StringUtils.hasText(defaultModelId)) {
                resolvedModelId = defaultModelId;
                resolved.setModelId(defaultModelId);
            }
        }

        return applyDefaults(resolved, resolvedModelId);
    }

    public LLMModelConfig resolveForWidget(String agentId, String userId, String modelId) {
        AgentWorkspaceEntity workspace = agentWorkspaceDomainService.findWorkspace(agentId, userId);
        LLMModelConfig baseConfig = workspace != null ? copyOf(workspace.getLlmModelConfig()) : null;
        if (baseConfig == null) {
            baseConfig = new LLMModelConfig();
        }
        baseConfig.setModelId(modelId);
        return applyDefaults(baseConfig, modelId);
    }

    public LLMModelConfig createDefault(String modelId) {
        LLMModelConfig config = new LLMModelConfig();
        config.setTemperature(DEFAULT_TEMPERATURE);
        config.setTopP(DEFAULT_TOP_P);
        config.setModelId(modelId);
        return applyDefaults(config, modelId);
    }

    public LLMModelConfig copyOf(LLMModelConfig source) {
        if (source == null) {
            return null;
        }
        LLMModelConfig copy = new LLMModelConfig();
        copy.setModelId(source.getModelId());
        copy.setTemperature(source.getTemperature());
        copy.setTopP(source.getTopP());
        copy.setTopK(source.getTopK());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setStrategyType(source.getStrategyType());
        copy.setReserveRatio(source.getReserveRatio());
        copy.setSummaryThreshold(source.getSummaryThreshold());
        return copy;
    }

    private LLMModelConfig applyDefaults(LLMModelConfig config, String modelId) {
        if (config == null) {
            config = new LLMModelConfig();
        }
        if (StringUtils.hasText(modelId)) {
            config.setModelId(modelId);
        }
        if (config.getTemperature() == null) {
            config.setTemperature(DEFAULT_TEMPERATURE);
        }
        if (config.getTopP() == null) {
            config.setTopP(DEFAULT_TOP_P);
        }
        if (config.getStrategyType() == null) {
            config.setStrategyType(TokenOverflowStrategyEnum.SLIDING_WINDOW);
        }
        if (config.getMaxTokens() == null || config.getMaxTokens() <= 0) {
            config.setMaxTokens(DEFAULT_MAX_TOKENS);
        }
        if (config.getReserveRatio() == null) {
            config.setReserveRatio(DEFAULT_RESERVE_RATIO);
        }
        if (config.getSummaryThreshold() == null || config.getSummaryThreshold() <= 0) {
            config.setSummaryThreshold(DEFAULT_SUMMARY_THRESHOLD);
        } else {
            int summaryThreshold = config.getSummaryThreshold();
            if (summaryThreshold < MIN_SUMMARY_THRESHOLD) {
                config.setSummaryThreshold(DEFAULT_SUMMARY_THRESHOLD);
            } else if (summaryThreshold > MAX_SUMMARY_THRESHOLD) {
                config.setSummaryThreshold(MAX_SUMMARY_THRESHOLD);
            }
        }
        return config;
    }

    private boolean isActiveChatModel(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return false;
        }
        ModelEntity model = llmDomainService.findModelById(modelId);
        return model != null && Boolean.TRUE.equals(model.getStatus()) && model.isChatType();
    }
}
