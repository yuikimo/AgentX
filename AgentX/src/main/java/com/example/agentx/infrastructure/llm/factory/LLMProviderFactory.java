package com.example.agentx.infrastructure.llm.factory;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.deepseek.DeepSeekThinkingHttpClientBuilder;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.time.Duration;
import java.util.Locale;

public class LLMProviderFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** 获取对应的服务商 不使用工厂模式，因为 OpenAiChatModel 没有无参构造器，并且其他类型的模型不能适配
     * @param protocol 协议
     * @param providerConfig 服务商信息 */
    public static ChatModel getLLMProvider(ProviderProtocol protocol, ProviderConfig providerConfig) {
        ChatModel model = null;
        if (protocol == ProviderProtocol.OPENAI) {
            OpenAiChatModel.OpenAiChatModelBuilder openAiChatModelBuilder = new OpenAiChatModel.OpenAiChatModelBuilder();
            if (requiresRequestSanitizer(providerConfig)) {
                openAiChatModelBuilder.httpClientBuilder(new DeepSeekThinkingHttpClientBuilder(
                        isDeepSeekModel(providerConfig)
                                && (providerConfig == null || !providerConfig.isDisableEnableThinking()),
                        providerConfig != null && providerConfig.isDisableEnableThinking()));
            }
            openAiChatModelBuilder.apiKey(providerConfig.getApiKey());
            openAiChatModelBuilder.baseUrl(providerConfig.getBaseUrl());
            openAiChatModelBuilder.customHeaders(providerConfig.getCustomHeaders());
            openAiChatModelBuilder.modelName(providerConfig.getModel());
            if (providerConfig.getResponseFormat() != null && !providerConfig.getResponseFormat().isBlank()) {
                openAiChatModelBuilder.responseFormat(providerConfig.getResponseFormat());
            }
            openAiChatModelBuilder.timeout(resolveTimeout(providerConfig));
            model = new OpenAiChatModel(openAiChatModelBuilder);
        } else if (protocol == ProviderProtocol.ANTHROPIC) {
            model = AnthropicChatModel.builder().apiKey(providerConfig.getApiKey()).baseUrl(providerConfig.getBaseUrl())
                    .modelName(providerConfig.getModel()).version("2023-06-01")
                    .cacheSystemMessages(providerConfig.isPromptCachingEnabled() && providerConfig.isCacheSystemMessages())
                    .cacheTools(providerConfig.isPromptCachingEnabled() && providerConfig.isCacheTools())
                    .timeout(resolveTimeout(providerConfig)).build();
        }
        return model;
    }

    public static StreamingChatModel getLLMProviderByStream(ProviderProtocol protocol, ProviderConfig providerConfig) {
        StreamingChatModel model = null;
        if (protocol == ProviderProtocol.OPENAI) {
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = new OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder();
            if (requiresRequestSanitizer(providerConfig)) {
                builder.httpClientBuilder(new DeepSeekThinkingHttpClientBuilder(
                        isDeepSeekModel(providerConfig)
                                && (providerConfig == null || !providerConfig.isDisableEnableThinking()),
                        providerConfig != null && providerConfig.isDisableEnableThinking()));
            }
            model = builder.apiKey(providerConfig.getApiKey())
                    .baseUrl(providerConfig.getBaseUrl()).customHeaders(providerConfig.getCustomHeaders())
                    .modelName(providerConfig.getModel()).timeout(resolveTimeout(providerConfig)).build();
        } else if (protocol == ProviderProtocol.ANTHROPIC) {
            model = AnthropicStreamingChatModel.builder().apiKey(providerConfig.getApiKey())
                    .baseUrl(providerConfig.getBaseUrl()).version("2023-06-01").modelName(providerConfig.getModel())
                    .cacheSystemMessages(providerConfig.isPromptCachingEnabled() && providerConfig.isCacheSystemMessages())
                    .cacheTools(providerConfig.isPromptCachingEnabled() && providerConfig.isCacheTools())
                    .timeout(resolveTimeout(providerConfig)).build();
        }

        return model;
    }

    private static Duration resolveTimeout(ProviderConfig providerConfig) {
        Duration timeout = providerConfig.getTimeout();
        return timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : DEFAULT_TIMEOUT;
    }

    private static boolean isDeepSeekModel(ProviderConfig providerConfig) {
        if (providerConfig == null || providerConfig.getModel() == null) {
            return false;
        }
        return providerConfig.getModel().toLowerCase(Locale.ROOT).contains("deepseek");
    }

    private static boolean requiresRequestSanitizer(ProviderConfig providerConfig) {
        return isDeepSeekModel(providerConfig)
                || (providerConfig != null && providerConfig.isDisableEnableThinking());
    }
}
