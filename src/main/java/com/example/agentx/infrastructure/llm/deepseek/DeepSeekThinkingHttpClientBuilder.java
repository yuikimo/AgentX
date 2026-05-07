package com.example.agentx.infrastructure.llm.deepseek;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;

import java.time.Duration;

/**
 * Wraps the default LangChain4j HTTP client builder and disables DeepSeek thinking mode
 * for OpenAI-compatible chat completion requests.
 */
public class DeepSeekThinkingHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;
    private final boolean disableDeepSeekThinking;
    private final boolean stripEnableThinking;

    public DeepSeekThinkingHttpClientBuilder() {
        this(HttpClientBuilderLoader.loadHttpClientBuilder(), true, false);
    }

    public DeepSeekThinkingHttpClientBuilder(HttpClientBuilder delegate) {
        this(delegate, true, false);
    }

    public DeepSeekThinkingHttpClientBuilder(boolean disableDeepSeekThinking, boolean stripEnableThinking) {
        this(HttpClientBuilderLoader.loadHttpClientBuilder(), disableDeepSeekThinking, stripEnableThinking);
    }

    public DeepSeekThinkingHttpClientBuilder(HttpClientBuilder delegate, boolean disableDeepSeekThinking,
            boolean stripEnableThinking) {
        this.delegate = delegate;
        this.disableDeepSeekThinking = disableDeepSeekThinking;
        this.stripEnableThinking = stripEnableThinking;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration duration) {
        delegate.connectTimeout(duration);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration duration) {
        delegate.readTimeout(duration);
        return this;
    }

    @Override
    public HttpClient build() {
        return new DeepSeekThinkingHttpClient(delegate.build(), disableDeepSeekThinking, stripEnableThinking);
    }
}
