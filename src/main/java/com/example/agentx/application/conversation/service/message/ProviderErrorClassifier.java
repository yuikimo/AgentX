package com.example.agentx.application.conversation.service.message;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 将各服务商返回的错误归一为统一分类，避免业务逻辑散落字符串判断。 */
@Component
public class ProviderErrorClassifier {

    public ProviderErrorType classify(Throwable throwable) {
        String normalizedMessage = collectThrowableMessage(throwable).toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(normalizedMessage)) {
            return ProviderErrorType.UNKNOWN;
        }
        if (isUnsupportedImage(normalizedMessage)) {
            return ProviderErrorType.UNSUPPORTED_IMAGE;
        }
        if (isContextOverflow(normalizedMessage)) {
            return ProviderErrorType.CONTEXT_OVERFLOW;
        }
        if (isHtmlResponse(normalizedMessage)) {
            return ProviderErrorType.HTML_RESPONSE;
        }
        return ProviderErrorType.UNKNOWN;
    }

    public String collectThrowableMessage(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 5) {
            if (StringUtils.isNotBlank(current.getMessage())) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    private boolean isUnsupportedImage(String lowerMessage) {
        return lowerMessage.contains("image_url") || lowerMessage.contains("expected `text`")
                || lowerMessage.contains("expected text") || lowerMessage.contains("unsupported image input")
                || lowerMessage.contains("only accepts text");
    }

    private boolean isContextOverflow(String lowerMessage) {
        return lowerMessage.contains("context length") || lowerMessage.contains("maximum context length")
                || lowerMessage.contains("context window") || lowerMessage.contains("too many tokens")
                || lowerMessage.contains("token limit exceeded");
    }

    private boolean isHtmlResponse(String lowerMessage) {
        return lowerMessage.contains("unexpected character ('<'") || lowerMessage.contains("<!doctype html")
                || lowerMessage.contains("<html") || lowerMessage.contains("text/html");
    }

    public enum ProviderErrorType {
        UNSUPPORTED_IMAGE,
        CONTEXT_OVERFLOW,
        HTML_RESPONSE,
        UNKNOWN
    }
}
