package com.example.agentx.infrastructure.llm;

import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.model.config.ProviderConfig;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.springframework.stereotype.Component;

/**
 * LLM服务工厂，用于创建LLM客户端
 */
@Component
public class LLMServiceFactory {

    /**
     * 获取流式LLM客户端
     *
     * @param provider 服务商实体
     * @param model    模型实体
     * @return 流式聊天语言模型
     */
    public StreamingChatLanguageModel getStreamingClient(ProviderEntity provider, ModelEntity model) {
        ProviderConfig config = provider.getConfig();

        ProviderConfig providerConfig = new ProviderConfig(
                config.getApiKey(),
                config.getBaseUrl(),
                model.getModelId(),
                provider.getProtocol());

        return LLMProviderService.getStream(provider.getProtocol(), providerConfig);
    }
}
