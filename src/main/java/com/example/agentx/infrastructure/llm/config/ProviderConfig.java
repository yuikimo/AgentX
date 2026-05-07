package com.example.agentx.infrastructure.llm.config;

import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class ProviderConfig {

    /** 密钥 */
    private final String apiKey;

    /** baseUrl */
    private final String baseUrl;

    /** 模型 */
    private String model;

    private ProviderProtocol protocol;

    /** provider 层 prompt caching 是否开启 */
    private boolean promptCachingEnabled = true;

    /** Anthropic: cache system messages */
    private boolean cacheSystemMessages = true;

    /** Anthropic: cache tools */
    private boolean cacheTools = true;

    /** OpenAI/OpenAI-compatible: response format, e.g. json_object */
    private String responseFormat;

    /** OpenAI-compatible: whether to strip enable_thinking from request payload */
    private boolean disableEnableThinking;

    /** LLM客户端请求超时 */
    private Duration timeout = Duration.ofMinutes(5);

    public ProviderProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(ProviderProtocol protocol) {
        this.protocol = protocol;
    }

    public void setCustomHeaders(Map<String, String> customHeaders) {
        this.customHeaders = customHeaders;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    private Map<String, String> customHeaders = new HashMap<>();

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ProviderConfig(String apiKey, String baseUrl, String model, ProviderProtocol protocol) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.protocol = protocol;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public void addCustomHeaders(String key, String value) {
        customHeaders.put(key, value);
    }

    public boolean isPromptCachingEnabled() {
        return promptCachingEnabled;
    }

    public void setPromptCachingEnabled(boolean promptCachingEnabled) {
        this.promptCachingEnabled = promptCachingEnabled;
    }

    public boolean isCacheSystemMessages() {
        return cacheSystemMessages;
    }

    public void setCacheSystemMessages(boolean cacheSystemMessages) {
        this.cacheSystemMessages = cacheSystemMessages;
    }

    public boolean isCacheTools() {
        return cacheTools;
    }

    public void setCacheTools(boolean cacheTools) {
        this.cacheTools = cacheTools;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isDisableEnableThinking() {
        return disableEnableThinking;
    }

    public void setDisableEnableThinking(boolean disableEnableThinking) {
        this.disableEnableThinking = disableEnableThinking;
    }
}
