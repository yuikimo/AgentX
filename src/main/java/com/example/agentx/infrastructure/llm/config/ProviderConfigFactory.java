package com.example.agentx.infrastructure.llm.config;

import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.time.Duration;

/** ProviderConfig 统一构建工厂 */
@Component
public class ProviderConfigFactory {

    public ProviderConfig fromChatContext(ChatContext chatContext) {
        if (chatContext == null) {
            return null;
        }
        ProviderEntity provider = chatContext.getOriginalProvider() != null ? chatContext.getOriginalProvider()
                : chatContext.getProvider();
        ModelEntity model = chatContext.getOriginalModel() != null ? chatContext.getOriginalModel()
                : chatContext.getModel();
        return fromProviderAndModel(provider, model);
    }

    public ProviderConfig fromProviderAndModel(ProviderEntity provider, ModelEntity model) {
        return fromProviderAndModel(provider, model, null);
    }

    public ProviderConfig fromProviderAndModel(ProviderEntity provider, ModelEntity model, Duration timeout) {
        if (provider == null || model == null || provider.getConfig() == null) {
            return null;
        }
        com.example.agentx.domain.llm.model.config.ProviderConfig rawConfig = provider.getConfig();
        ProviderConfig providerConfig = new ProviderConfig(rawConfig.getApiKey(), rawConfig.getBaseUrl(),
                model.getModelEndpoint(), provider.getProtocol());
        applyDefaults(providerConfig, timeout);
        return providerConfig;
    }

    public ProviderConfig fromModelConfig(ModelConfig modelConfig, Duration timeout) {
        if (modelConfig == null) {
            return null;
        }
        ProviderConfig providerConfig = new ProviderConfig(modelConfig.getApiKey(), modelConfig.getBaseUrl(),
                modelConfig.getModelEndpoint(), modelConfig.getProtocol());
        if (modelConfig.getModelType() == ModelType.OCR) {
            providerConfig.setDisableEnableThinking(true);
        }
        applyDefaults(providerConfig, timeout);
        return providerConfig;
    }

    private void applyDefaults(ProviderConfig providerConfig, Duration timeout) {
        if (providerConfig == null) {
            return;
        }
        providerConfig.setPromptCachingEnabled(supportsPromptCaching(providerConfig.getProtocol()));
        providerConfig.setCacheSystemMessages(providerConfig.isPromptCachingEnabled());
        providerConfig.setCacheTools(providerConfig.isPromptCachingEnabled());
        if (timeout != null) {
            providerConfig.setTimeout(timeout);
        }
    }

    private boolean supportsPromptCaching(ProviderProtocol protocol) {
        return protocol == ProviderProtocol.ANTHROPIC;
    }
}
