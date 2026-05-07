package com.example.agentx.infrastructure.llm.deepseek;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites DeepSeek chat completion requests to disable thinking mode:
 * {"thinking":{"type":"disabled"}}
 */
public class DeepSeekThinkingHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekThinkingHttpClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String THINKING_FIELD = "thinking";
    private static final String ENABLE_THINKING_FIELD = "enable_thinking";
    private static final String TYPE_FIELD = "type";
    private static final String DISABLED_VALUE = "disabled";

    private final HttpClient delegate;
    private final boolean disableDeepSeekThinking;
    private final boolean stripEnableThinking;

    public DeepSeekThinkingHttpClient(HttpClient delegate) {
        this(delegate, true, false);
    }

    public DeepSeekThinkingHttpClient(HttpClient delegate, boolean disableDeepSeekThinking,
            boolean stripEnableThinking) {
        this.delegate = delegate;
        this.disableDeepSeekThinking = disableDeepSeekThinking;
        this.stripEnableThinking = stripEnableThinking;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        return delegate.execute(rewriteRequestIfNeeded(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(rewriteRequestIfNeeded(request), parser, listener);
    }

    private HttpRequest rewriteRequestIfNeeded(HttpRequest request) {
        if (request == null || request.url() == null || !isChatCompletionsRequest(request.url())) {
            return request;
        }
        String rewrittenBody = rewriteBodyIfNeeded(request.body());
        if (rewrittenBody == null || rewrittenBody.equals(request.body())) {
            return request;
        }
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(request.headers())
                .body(rewrittenBody)
                .build();
    }

    private String rewriteBodyIfNeeded(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        Map<String, Object> payload = JsonUtils.parseMap(body);
        if (payload == null) {
            return body;
        }
        boolean changed = false;
        if (stripEnableThinking && payload.containsKey(ENABLE_THINKING_FIELD)) {
            payload.remove(ENABLE_THINKING_FIELD);
            changed = true;
            log.debug("已从OpenAI兼容请求中移除 enable_thinking 参数: model={}", payload.get("model"));
        }
        if (stripEnableThinking && payload.containsKey(THINKING_FIELD)) {
            payload.remove(THINKING_FIELD);
            changed = true;
            log.debug("已从OCR/兼容请求中移除 thinking 参数: model={}", payload.get("model"));
        }
        Object model = payload.get("model");
        if (disableDeepSeekThinking && model instanceof String modelName && isDeepSeekModel(modelName)) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put(TYPE_FIELD, DISABLED_VALUE);
            payload.put(THINKING_FIELD, thinking);
            changed = true;
            log.debug("已为DeepSeek请求注入 thinking=disabled: model={}", modelName);
        }
        return changed ? JsonUtils.toJsonString(payload) : body;
    }

    private boolean isChatCompletionsRequest(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return false;
            }
            String normalizedPath = path.endsWith("/") && path.length() > 1
                    ? path.substring(0, path.length() - 1)
                    : path;
            return normalizedPath.endsWith(CHAT_COMPLETIONS_PATH);
        } catch (Exception ignored) {
            String normalizedUrl = url.endsWith("/") && url.length() > 1 ? url.substring(0, url.length() - 1) : url;
            return normalizedUrl.contains(CHAT_COMPLETIONS_PATH);
        }
    }

    private boolean isDeepSeekModel(String modelName) {
        return modelName != null && modelName.toLowerCase(Locale.ROOT).contains("deepseek");
    }
}
