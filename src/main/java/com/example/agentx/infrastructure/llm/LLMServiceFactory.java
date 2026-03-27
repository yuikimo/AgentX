package com.example.agentx.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Component;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;

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
    public StreamingChatModel getStreamingClient(ProviderEntity provider, ModelEntity model) {
        com.example.agentx.domain.llm.model.config.ProviderConfig config = provider.getConfig();

        ProviderConfig providerConfig = new ProviderConfig(config.getApiKey(), config.getBaseUrl(),
                model.getModelEndpoint(), provider.getProtocol());

        return LLMProviderService.getStream(provider.getProtocol(), providerConfig);
    }

    /**
     * 获取标准LLM客户端
     *
     * @param provider 服务商实体
     * @param model    模型实体
     * @return 流式聊天语言模型
     */
    public ChatModel getStrandClient(ProviderEntity provider, ModelEntity model) {
        com.example.agentx.domain.llm.model.config.ProviderConfig config = provider.getConfig();

        ProviderConfig providerConfig = new ProviderConfig(config.getApiKey(), config.getBaseUrl(),
                model.getModelEndpoint(), provider.getProtocol());

        return LLMProviderService.getStrand(provider.getProtocol(), providerConfig);
    }
}