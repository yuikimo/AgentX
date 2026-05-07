package com.example.agentx.infrastructure.llm;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.factory.LLMProviderFactory;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class LLMProviderService {

    private static final Cache<String, ChatModel> CHAT_MODEL_CACHE = CacheBuilder.newBuilder().maximumSize(256)
            .expireAfterAccess(Duration.ofMinutes(30)).build();
    private static final Cache<String, StreamingChatModel> STREAM_MODEL_CACHE = CacheBuilder.newBuilder()
            .maximumSize(256).expireAfterAccess(Duration.ofMinutes(30)).build();

    public static ChatModel getStrand(ProviderProtocol protocol, ProviderConfig providerConfig) {
        String cacheKey = buildCacheKey(protocol, providerConfig);
        ChatModel cached = CHAT_MODEL_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        ChatModel created = LLMProviderFactory.getLLMProvider(protocol, snapshot(providerConfig));
        if (created != null) {
            CHAT_MODEL_CACHE.put(cacheKey, created);
        }
        return created;
    }

    public static StreamingChatModel getStream(ProviderProtocol protocol, ProviderConfig providerConfig) {
        String cacheKey = buildCacheKey(protocol, providerConfig);
        StreamingChatModel cached = STREAM_MODEL_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        StreamingChatModel created = LLMProviderFactory.getLLMProviderByStream(protocol, snapshot(providerConfig));
        if (created != null) {
            STREAM_MODEL_CACHE.put(cacheKey, created);
        }
        return created;
    }

    private static String buildCacheKey(ProviderProtocol protocol, ProviderConfig providerConfig) {
        String headers = providerConfig.getCustomHeaders().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&"));
        return protocol + "|" + providerConfig.getApiKey() + "|" + providerConfig.getBaseUrl() + "|"
                + providerConfig.getModel() + "|" + headers + "|" + providerConfig.isPromptCachingEnabled() + "|"
                + providerConfig.isCacheSystemMessages() + "|" + providerConfig.isCacheTools() + "|"
                + providerConfig.getResponseFormat() + "|" + providerConfig.getTimeout() + "|"
                + providerConfig.isDisableEnableThinking();
    }

    private static ProviderConfig snapshot(ProviderConfig providerConfig) {
        ProviderConfig snapshot = new ProviderConfig(providerConfig.getApiKey(), providerConfig.getBaseUrl(),
                providerConfig.getModel(), providerConfig.getProtocol());
        snapshot.setCustomHeaders(providerConfig.getCustomHeaders());
        snapshot.setPromptCachingEnabled(providerConfig.isPromptCachingEnabled());
        snapshot.setCacheSystemMessages(providerConfig.isCacheSystemMessages());
        snapshot.setCacheTools(providerConfig.isCacheTools());
        snapshot.setResponseFormat(providerConfig.getResponseFormat());
        snapshot.setTimeout(providerConfig.getTimeout());
        snapshot.setDisableEnableThinking(providerConfig.isDisableEnableThinking());
        return snapshot;
    }
}
