package com.example.agentx.infrastructure.llm;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 兼容部分 OpenAI 元数据实现中 tokenUsage 强转为 OpenAiTokenUsage 的场景。
 * 当底层实际存放的是通用 TokenUsage 时，response.tokenUsage() / metadata.tokenUsage()
 * 可能抛出 ClassCastException，这里统一回退到反射读取基类字段。
 */
public final class ChatResponseTokenUsageUtils {

    private static final Logger logger = LoggerFactory.getLogger(ChatResponseTokenUsageUtils.class);

    private static final Field TOKEN_USAGE_FIELD;

    static {
        try {
            TOKEN_USAGE_FIELD = ChatResponseMetadata.class.getDeclaredField("tokenUsage");
            TOKEN_USAGE_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("无法初始化 ChatResponseMetadata.tokenUsage 访问器", e);
        }
    }

    private ChatResponseTokenUsageUtils() {
    }

    public static TokenUsage getTokenUsage(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return null;
        }
        try {
            return chatResponse.tokenUsage();
        } catch (ClassCastException e) {
            ChatResponseMetadata metadata = chatResponse.metadata();
            TokenUsage fallback = extractTokenUsage(metadata);
            if (fallback != null) {
                logger.warn("检测到不兼容的 tokenUsage 类型，已回退为基类读取: metadataType={}",
                        metadata != null ? metadata.getClass().getName() : "null");
                return fallback;
            }
            logger.warn("读取 tokenUsage 失败，回退为空: {}", e.getMessage());
            return null;
        }
    }

    public static Integer inputTokenCount(ChatResponse chatResponse) {
        TokenUsage tokenUsage = getTokenUsage(chatResponse);
        return tokenUsage != null ? tokenUsage.inputTokenCount() : null;
    }

    public static Integer outputTokenCount(ChatResponse chatResponse) {
        TokenUsage tokenUsage = getTokenUsage(chatResponse);
        return tokenUsage != null ? tokenUsage.outputTokenCount() : null;
    }

    private static TokenUsage extractTokenUsage(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            Object raw = TOKEN_USAGE_FIELD.get(metadata);
            return raw instanceof TokenUsage tokenUsage ? tokenUsage : null;
        } catch (IllegalAccessException e) {
            logger.warn("反射读取 tokenUsage 失败: {}", e.getMessage());
            return null;
        }
    }
}
