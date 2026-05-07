package com.example.agentx.application.conversation.util;

import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.exception.EntityNotFoundException;
import com.example.agentx.infrastructure.exception.InsufficientBalanceException;
import com.example.agentx.infrastructure.exception.ParamValidationException;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** 统一构建对前端友好的结构化错误消息。 */
public final class ChatErrorResponseFactory {

    public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String CODE_MODEL_TIMEOUT = "MODEL_TIMEOUT";
    public static final String CODE_VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String CODE_RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String CODE_INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    public static final String CODE_CONVERSATION_CONFLICT = "CONVERSATION_CONFLICT";

    private ChatErrorResponseFactory() {
    }

    public static AgentChatResponse fromThrowable(Throwable throwable) {
        ErrorDescriptor descriptor = classify(throwable);
        return build(descriptor.code(), descriptor.userMessage());
    }

    public static AgentChatResponse build(String code, String userMessage) {
        String resolvedMessage = StringUtils.defaultIfBlank(userMessage, "服务暂时不可用，请稍后再试");
        AgentChatResponse response = AgentChatResponse.buildEndMessage(resolvedMessage, MessageType.ERROR);
        response.setErrorCode(StringUtils.defaultIfBlank(code, CODE_INTERNAL_ERROR));
        response.setUserMessage(resolvedMessage);
        return response;
    }

    public static AgentChatResponse buildConversationConflict() {
        return build(CODE_CONVERSATION_CONFLICT, "当前会话已有请求正在处理中，请稍后再试");
    }

    public static AgentChatResponse buildTimeout() {
        return build(CODE_MODEL_TIMEOUT, "请求处理超时，请重试");
    }

    public static AgentChatResponse buildInsufficientBalance(BigDecimal balance) {
        String balanceText = balance == null ? "" : "，当前余额：" + balance + "元";
        return build(CODE_INSUFFICIENT_BALANCE, "账户余额不足" + balanceText + "，请充值后继续使用");
    }

    private static ErrorDescriptor classify(Throwable throwable) {
        if (throwable == null) {
            return new ErrorDescriptor(CODE_INTERNAL_ERROR, "服务暂时不可用，请稍后再试");
        }

        if (throwable instanceof InsufficientBalanceException || contains(throwable.getMessage(), "余额不足")) {
            return new ErrorDescriptor(CODE_INSUFFICIENT_BALANCE, "账户余额不足，请充值后继续使用");
        }
        if (throwable instanceof ParamValidationException || throwable instanceof MethodArgumentNotValidException
                || throwable instanceof BindException) {
            return new ErrorDescriptor(CODE_VALIDATION_ERROR, resolveSafeMessage(throwable.getMessage(), "请求参数无效，请检查后重试"));
        }
        if (throwable instanceof EntityNotFoundException) {
            return new ErrorDescriptor(CODE_RESOURCE_NOT_FOUND, resolveSafeMessage(throwable.getMessage(), "请求的资源不存在或已删除"));
        }
        if (throwable instanceof AsyncRequestTimeoutException || throwable instanceof TimeoutException
                || contains(throwable.getMessage(), "timeout", "timed out", "超时")) {
            return new ErrorDescriptor(CODE_MODEL_TIMEOUT, "模型响应超时，请稍后重试");
        }
        if (contains(throwable.getMessage(), "已有活跃连接", "活跃连接", "duplicate")) {
            return new ErrorDescriptor(CODE_CONVERSATION_CONFLICT, "当前会话已有请求正在处理中，请稍后再试");
        }
        if (throwable instanceof BusinessException && !looksSensitive(throwable.getMessage())) {
            return new ErrorDescriptor(CODE_INTERNAL_ERROR, resolveSafeMessage(throwable.getMessage(), "请求处理失败，请稍后重试"));
        }
        return new ErrorDescriptor(CODE_INTERNAL_ERROR, "服务暂时不可用，请稍后再试");
    }

    private static String resolveSafeMessage(String message, String fallback) {
        if (StringUtils.isBlank(message) || looksSensitive(message)) {
            return fallback;
        }
        return message.trim();
    }

    private static boolean looksSensitive(String message) {
        if (StringUtils.isBlank(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("exception")
                || normalized.contains("psqlexception")
                || normalized.contains("badsqlgrammar")
                || normalized.contains("sqlstate")
                || normalized.contains("jdbc")
                || normalized.contains("org.springframework")
                || normalized.contains("java.")
                || normalized.contains("stack trace")
                || normalized.contains("syntax error")
                || normalized.contains("error updating database")
                || normalized.contains("nullpointer");
    }

    private static boolean contains(String message, String... patterns) {
        if (StringUtils.isBlank(message) || patterns == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern != null && normalized.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private record ErrorDescriptor(String code, String userMessage) {
    }
}
