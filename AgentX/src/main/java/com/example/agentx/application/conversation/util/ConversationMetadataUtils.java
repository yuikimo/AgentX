package com.example.agentx.application.conversation.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对话消息 metadata 工具 */
public final class ConversationMetadataUtils {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMetadataUtils.class);
    private ConversationMetadataUtils() {
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(json);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            logger.warn("解析消息metadata失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public static String writeJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            String json = JsonUtils.toJsonString(metadata);
            return "{}".equals(json) ? null : json;
        } catch (Exception e) {
            logger.warn("序列化消息metadata失败: {}", e.getMessage());
            return null;
        }
    }

    public static String mergeJson(String existingJson, Map<String, Object> additions) {
        if (additions == null || additions.isEmpty()) {
            return existingJson;
        }
        Map<String, Object> merged = readMap(existingJson);
        merged.putAll(additions);
        return writeJson(merged);
    }

    public static Map<String, Object> singleton(String key, Object value) {
        if (key == null || key.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
