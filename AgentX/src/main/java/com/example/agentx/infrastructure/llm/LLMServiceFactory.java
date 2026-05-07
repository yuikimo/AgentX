package com.example.agentx.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Component;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.infrastructure.llm.config.LlmChatProperties;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;

import java.time.Duration;

/** LLM服务工厂，用于创建LLM客户端 */
@Component
public class LLMServiceFactory {

    private final ProviderConfigFactory providerConfigFactory;
    private final LlmChatProperties llmChatProperties;

    public LLMServiceFactory(ProviderConfigFactory providerConfigFactory, LlmChatProperties llmChatProperties) {
        this.providerConfigFactory = providerConfigFactory;
        this.llmChatProperties = llmChatProperties;
    }

    /** 获取流式LLM客户端
     * 
     * @param provider 服务商实体
     * @param model 模型实体
     * @return 流式聊天语言模型 */
    public StreamingChatModel getStreamingClient(ProviderEntity provider, ModelEntity model) {
        return getStreamingClient(provider, model, Duration.ofSeconds(getDefaultStreamingTimeoutSeconds()));
    }

    public StreamingChatModel getStreamingClient(ProviderEntity provider, ModelEntity model, Duration timeout) {
        ProviderConfig providerConfig = providerConfigFactory.fromProviderAndModel(provider, model, timeout);
        return SafeStreamingChatModel.wrap(LLMProviderService.getStream(provider.getProtocol(), providerConfig));
    }

    /** 获取标准LLM客户端
     *
     * @param provider 服务商实体
     * @param model 模型实体
     * @return 流式聊天语言模型 */
    public ChatModel getStrandClient(ProviderEntity provider, ModelEntity model) {
        return getStrandClient(provider, model, Duration.ofSeconds(getDefaultChatTimeoutSeconds()));
    }

    public ChatModel getStrandClient(ProviderEntity provider, ModelEntity model, Duration timeout) {
        ProviderConfig providerConfig = providerConfigFactory.fromProviderAndModel(provider, model, timeout);
        return LLMProviderService.getStrand(provider.getProtocol(), providerConfig);
    }

    public long getDefaultChatTimeoutSeconds() {
        return Math.max(1, llmChatProperties.getDefaultTimeoutSeconds());
    }

    public long getDefaultStreamingTimeoutSeconds() {
        return Math.max(1, llmChatProperties.getDefaultStreamTimeoutSeconds());
    }
}
