package com.example.agentx.application.conversation.service.message;

import org.apache.commons.lang3.StringUtils;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 工具调用相关的 payload/trace 统一处理工具。 */
public final class ToolPayloadUtils {

    public static final String TOOL_CALL_LIMIT_EXCEEDED_CODE = "TOOL_CALL_LIMIT_EXCEEDED";
    private static final int DEFAULT_ARGUMENT_LIMIT = 800;
    private static final int DEFAULT_RESULT_LIMIT = 1500;
    private static final int TRACE_ARGUMENT_LIMIT = 4000;
    private static final int TRACE_RESULT_LIMIT = 8000;
    private static final List<JsonCompactionProfile> JSON_COMPACTION_PROFILES = List.of(
            new JsonCompactionProfile(3, 12, 5, 220),
            new JsonCompactionProfile(2, 8, 3, 140),
            new JsonCompactionProfile(2, 5, 2, 90),
            new JsonCompactionProfile(1, 4, 1, 60));
    private static final List<PatternReplacement> SENSITIVE_VALUE_PATTERNS = List.of(
            new PatternReplacement(
                    Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password)=)([^&\\s]+)"),
                    "$1***"),
            new PatternReplacement(Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([^\\s\",]+)"), "$1***"),
            new PatternReplacement(
                    Pattern.compile(
                            "(?i)(\"(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization)\"\\s*:\\s*\")([^\"]+)(\")"),
                    "$1***$3"),
            new PatternReplacement(
                    Pattern.compile(
                            "(?i)('(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization)'\\s*:\\s*')([^']+)(')"),
                    "$1***$3"));

    private ToolPayloadUtils() {
    }

    public static boolean isToolExecutionSuccessful(String result) {
        return parseToolExecutionResult(result).success();
    }

    public static String classifyToolError(String result) {
        ToolExecutionResult toolExecutionResult = parseToolExecutionResult(result);
        if (toolExecutionResult.success()) {
            return "";
        }
        if (StringUtils.isNotBlank(toolExecutionResult.errorCode())) {
            return toolExecutionResult.errorCode();
        }
        String normalized = StringUtils.defaultString(result).trim();
        if (containsAnyIgnoreCase(normalized, "超时", "timeout", "timed out")) {
            return "timeout";
        }
        if (containsAnyIgnoreCase(normalized, "达到上限", "达上限", "预算", "budget", "limit exceeded", "too many")) {
            return "budget_exhausted";
        }
        if (containsAnyIgnoreCase(normalized, "不可用", "初始化", "unavailable", "not available", "connection refused")) {
            return "unavailable";
        }
        if (containsAnyIgnoreCase(normalized, "中断", "interrupted", "cancelled", "canceled")) {
            return "interrupted";
        }
        return "execution_failed";
    }

    public static String abbreviateForPayload(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", "\\n");
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    public static String sanitizeArgumentsForTrace(String arguments) {
        return sanitizeForTrace(arguments, TRACE_ARGUMENT_LIMIT);
    }

    public static String sanitizeResultForTrace(String result) {
        return sanitizeForTrace(result, TRACE_RESULT_LIMIT);
    }

    public static String buildSingleToolPayload(String arguments, String result, Integer durationMs) {
        ToolExecutionResult toolExecutionResult = parseToolExecutionResult(result);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arguments", sanitizeForPayload(arguments, DEFAULT_ARGUMENT_LIMIT));
        payload.put("result", sanitizeForPayload(result, DEFAULT_RESULT_LIMIT));
        payload.put("success", toolExecutionResult.success());
        if (durationMs != null) {
            payload.put("durationMs", Math.max(0, durationMs));
        }
        if (StringUtils.isNotBlank(toolExecutionResult.errorCode())) {
            payload.put("errorCode", toolExecutionResult.errorCode());
        }
        if (!toolExecutionResult.success() && StringUtils.isNotBlank(toolExecutionResult.message())) {
            payload.put("errorMessage", sanitizeForPayload(toolExecutionResult.message(), DEFAULT_RESULT_LIMIT));
        }
        String errorCategory = classifyToolError(result);
        if (StringUtils.isNotBlank(errorCategory)) {
            payload.put("errorCategory", errorCategory);
        }
        return toJson(payload);
    }

    public static String buildSinglePendingToolPayload(String name, String arguments, Integer durationMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", StringUtils.defaultIfBlank(name, "unknown"));
        payload.put("arguments", sanitizeForPayload(arguments, DEFAULT_ARGUMENT_LIMIT));
        payload.put("status", "running");
        if (durationMs != null) {
            payload.put("durationMs", Math.max(0, durationMs));
        }
        return toJson(payload);
    }

    public static String buildMultiToolPayload(List<ToolPayloadItem> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>(toolCalls.size());
        for (ToolPayloadItem toolCall : toolCalls) {
            ToolExecutionResult toolExecutionResult = parseToolExecutionResult(toolCall.result());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", StringUtils.defaultIfBlank(toolCall.name(), "unknown"));
            item.put("arguments", sanitizeForPayload(toolCall.arguments(), DEFAULT_ARGUMENT_LIMIT));
            item.put("result", sanitizeForPayload(toolCall.result(), DEFAULT_RESULT_LIMIT));
            item.put("success", toolExecutionResult.success());
            if (toolCall.durationMs() != null) {
                item.put("durationMs", Math.max(0, toolCall.durationMs()));
            }
            if (StringUtils.isNotBlank(toolExecutionResult.errorCode())) {
                item.put("errorCode", toolExecutionResult.errorCode());
            }
            if (!toolExecutionResult.success() && StringUtils.isNotBlank(toolExecutionResult.message())) {
                item.put("errorMessage", sanitizeForPayload(toolExecutionResult.message(), DEFAULT_RESULT_LIMIT));
            }
            String errorCategory = classifyToolError(toolCall.result());
            if (StringUtils.isNotBlank(errorCategory)) {
                item.put("errorCategory", errorCategory);
            }
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCalls", items);
        return toJson(payload);
    }

    public static ToolExecutionResult parseToolExecutionResult(String result) {
        String raw = StringUtils.defaultString(result);
        String normalized = raw.trim();
        if (StringUtils.isBlank(normalized)) {
            return new ToolExecutionResult(true, "", "", raw);
        }
        if (normalized.startsWith("✅")) {
            return new ToolExecutionResult(true, "", stripLeadingMarker(normalized, "✅"), raw);
        }
        if (normalized.startsWith("❌")) {
            String message = stripLeadingMarker(normalized, "❌");
            return new ToolExecutionResult(false, detectErrorCode(message), message, raw);
        }

        ToolExecutionResult jsonResult = parseJsonToolExecutionResult(normalized, raw);
        if (jsonResult != null) {
            return jsonResult;
        }

        if (looksLikeFailureText(normalized)) {
            return new ToolExecutionResult(false, detectErrorCode(normalized), normalized, raw);
        }
        return new ToolExecutionResult(true, "", normalized, raw);
    }

    public static String truncateToolResult(String result, int maxChars, String suffix) {
        if (result == null || result.length() <= maxChars) {
            return result;
        }
        String resolvedSuffix = StringUtils.defaultString(suffix);
        int contentLimit = Math.max(0, maxChars - resolvedSuffix.length());
        if (contentLimit <= 0) {
            return resolvedSuffix.substring(0, Math.min(resolvedSuffix.length(), maxChars));
        }
        String safePrefix = trimToStructuredBoundary(result, contentLimit);
        return safePrefix + resolvedSuffix;
    }

    public static String buildPendingToolPayload(List<PendingToolPayloadItem> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>(toolCalls.size());
        for (PendingToolPayloadItem toolCall : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", StringUtils.defaultIfBlank(toolCall.name(), "unknown"));
            item.put("arguments", sanitizeForPayload(toolCall.arguments(), DEFAULT_ARGUMENT_LIMIT));
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCalls", items);
        return toJson(payload);
    }

    private static String sanitizeForTrace(String value, int limit) {
        if (value == null) {
            return null;
        }
        String sanitized = sanitizeSensitive(value.replace("\r", ""));
        if (sanitized.length() <= limit) {
            return sanitized;
        }
        return sanitized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static String sanitizeForPayload(String value, int limit) {
        if (value == null) {
            return "";
        }
        String sanitized = sanitizeSensitive(value);
        if (sanitized.length() <= limit) {
            return abbreviateForPayload(sanitized, limit);
        }
        String compactJson = abbreviateStructuredJsonForPayload(sanitized, limit);
        if (StringUtils.isNotBlank(compactJson)) {
            return compactJson;
        }
        return abbreviateForPayload(sanitized, limit);
    }

    private static String sanitizeSensitive(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        for (PatternReplacement patternReplacement : SENSITIVE_VALUE_PATTERNS) {
            sanitized = patternReplacement.pattern().matcher(sanitized).replaceAll(patternReplacement.replacement());
        }
        return sanitized;
    }

    private static String toJson(Map<String, Object> payload) {
        try {
            return JsonUtils.toJsonString(payload);
        } catch (Exception e) {
            return fallbackJson(payload);
        }
    }

    private static String fallbackJson(Map<String, Object> payload) {
        String arguments = abbreviateForPayload((String) payload.get("arguments"), DEFAULT_ARGUMENT_LIMIT);
        String result = abbreviateForPayload((String) payload.get("result"), DEFAULT_RESULT_LIMIT);
        boolean success = payload.get("success") instanceof Boolean booleanValue && booleanValue;
        return "{\"arguments\":\"" + escapeJson(arguments) + "\",\"result\":\"" + escapeJson(result)
                + "\",\"success\":" + success + "}";
    }

    private static String escapeJson(String value) {
        return StringUtils.defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String abbreviateStructuredJsonForPayload(String value, int limit) {
        String normalized = StringUtils.defaultString(value).trim();
        if (!looksLikeJsonContainer(normalized)) {
            return null;
        }
        Object parsed = JsonUtils.parseObject(normalized, Object.class);
        if (!(parsed instanceof Map<?, ?>) && !(parsed instanceof List<?>)) {
            return null;
        }

        String serialized = JsonUtils.toJsonString(parsed);
        if (serialized.length() <= limit) {
            return serialized;
        }
        for (JsonCompactionProfile profile : JSON_COMPACTION_PROFILES) {
            Object compacted = compactJsonValue(parsed, profile, 0);
            String compactedJson = JsonUtils.toJsonString(compacted);
            if (compactedJson.length() <= limit) {
                return compactedJson;
            }
        }
        return JsonUtils.toJsonString(buildMinimalJsonTruncationMarker(parsed));
    }

    private static boolean looksLikeJsonContainer(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        return value.startsWith("{") && value.endsWith("}") || value.startsWith("[") && value.endsWith("]");
    }

    private static Object compactJsonValue(Object value, JsonCompactionProfile profile, int depth) {
        if (value instanceof Map<?, ?> mapValue) {
            return compactJsonMap(mapValue, profile, depth);
        }
        if (value instanceof List<?> listValue) {
            return compactJsonList(listValue, profile, depth);
        }
        if (value instanceof String stringValue) {
            return abbreviateForJsonValue(stringValue, profile.maxStringChars());
        }
        return value;
    }

    private static Map<String, Object> compactJsonMap(Map<?, ?> source, JsonCompactionProfile profile, int depth) {
        LinkedHashMap<String, Object> compacted = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return compacted;
        }
        if (depth >= profile.maxDepth()) {
            compacted.put("_truncated", true);
            compacted.put("_omittedFields", source.size());
            return compacted;
        }

        int included = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (included >= profile.maxObjectEntries()) {
                break;
            }
            String key = entry.getKey() == null ? "null" : String.valueOf(entry.getKey());
            compacted.put(key, compactJsonValue(entry.getValue(), profile, depth + 1));
            included++;
        }
        int omitted = source.size() - included;
        if (omitted > 0) {
            compacted.put("_truncated", true);
            compacted.put("_omittedFields", omitted);
        }
        return compacted;
    }

    private static List<Object> compactJsonList(List<?> source, JsonCompactionProfile profile, int depth) {
        List<Object> compacted = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return compacted;
        }
        if (depth >= profile.maxDepth()) {
            compacted.add(Map.of("_truncated", true, "_omittedItems", source.size()));
            return compacted;
        }

        int included = Math.min(source.size(), profile.maxArrayItems());
        for (int index = 0; index < included; index++) {
            compacted.add(compactJsonValue(source.get(index), profile, depth + 1));
        }
        int omitted = source.size() - included;
        if (omitted > 0) {
            compacted.add(Map.of("_truncated", true, "_omittedItems", omitted));
        }
        return compacted;
    }

    private static String abbreviateForJsonValue(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", "\\n");
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static Object buildMinimalJsonTruncationMarker(Object parsed) {
        LinkedHashMap<String, Object> marker = new LinkedHashMap<>();
        marker.put("_truncated", true);
        if (parsed instanceof Map<?, ?> mapValue) {
            marker.put("_type", "object");
            marker.put("_omittedFields", mapValue.size());
            return marker;
        }
        if (parsed instanceof List<?> listValue) {
            marker.put("_type", "array");
            marker.put("_omittedItems", listValue.size());
            return List.of(marker);
        }
        marker.put("_type", "json");
        return marker;
    }

    private static ToolExecutionResult parseJsonToolExecutionResult(String normalized, String raw) {
        if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
            return null;
        }
        Map<String, Object> parsed = parseJsonMapLenient(normalized);
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }

        Boolean successValue = resolveSuccessValue(parsed.get("success"));
        String message = firstNonBlank(parsed.get("errorMessage"), parsed.get("error"), parsed.get("message"),
                parsed.get("msg"));
        String errorCode = firstNonBlank(parsed.get("errorCode"), parsed.get("code"), parsed.get("errorType"),
                parsed.get("error_type"));
        String status = firstNonBlank(parsed.get("status"));

        if (successValue != null) {
            return new ToolExecutionResult(successValue, successValue ? "" : normalizeErrorCode(errorCode, message),
                    StringUtils.defaultIfBlank(message, normalized), raw);
        }
        if (StringUtils.isNotBlank(status)
                && List.of("error", "failed", "failure", "timeout").contains(status.toLowerCase(Locale.ROOT))) {
            return new ToolExecutionResult(false, normalizeErrorCode(errorCode, message),
                    StringUtils.defaultIfBlank(message, normalized), raw);
        }
        if (StringUtils.isNotBlank(errorCode) || StringUtils.isNotBlank(message) && parsed.containsKey("error")) {
            return new ToolExecutionResult(false, normalizeErrorCode(errorCode, message),
                    StringUtils.defaultIfBlank(message, normalized), raw);
        }
        return null;
    }

    private static Map<String, Object> parseJsonMapLenient(String normalized) {
        Map<String, Object> parsed = JsonUtils.parseMap(normalized);
        if (parsed != null && !parsed.isEmpty()) {
            return parsed;
        }

        String unwrapped = unwrapQuotedJsonString(normalized);
        if (!StringUtils.equals(unwrapped, normalized)) {
            parsed = JsonUtils.parseMap(unwrapped);
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        }

        String normalizedEscapes = normalizeEscapedJsonContainer(normalized);
        if (!StringUtils.equals(normalizedEscapes, normalized)) {
            parsed = JsonUtils.parseMap(normalizedEscapes);
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        }

        if (!StringUtils.equals(unwrapped, normalized)) {
            String unwrappedNormalizedEscapes = normalizeEscapedJsonContainer(unwrapped);
            if (!StringUtils.equals(unwrappedNormalizedEscapes, unwrapped)) {
                parsed = JsonUtils.parseMap(unwrappedNormalizedEscapes);
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            }
        }

        return null;
    }

    private static String unwrapQuotedJsonString(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (!(trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return value;
        }
        String unwrapped = JsonUtils.parseObject(trimmed, String.class);
        if (StringUtils.isBlank(unwrapped)) {
            return value;
        }
        String normalized = unwrapped.trim();
        return looksLikeJsonContainer(normalized) ? normalized : value;
    }

    private static String normalizeEscapedJsonContainer(String value) {
        if (StringUtils.isBlank(value) || !value.contains("\\")) {
            return value;
        }
        return value.replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static Boolean resolveSuccessValue(Object rawValue) {
        if (rawValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (rawValue instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(stringValue)) {
                return false;
            }
        }
        return null;
    }

    private static boolean looksLikeFailureText(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.startsWith("error:")
                || lower.startsWith("failed:")
                || lower.startsWith("failure:")
                || lower.startsWith("exception:")
                || lower.startsWith("rpc error")
                || lower.startsWith("timeout")
                || lower.contains(" traceback")
                || lower.contains("exception")
                || lower.contains("permission denied");
    }

    private static String detectErrorCode(String message) {
        String normalized = StringUtils.defaultString(message).trim();
        if (containsAnyIgnoreCase(normalized, "工具调用次数已达到上限", "工具调用次数已达上限", "tool call limit",
                "too many tool calls")) {
            return TOOL_CALL_LIMIT_EXCEEDED_CODE;
        }
        if (containsAnyIgnoreCase(normalized, "超时", "timeout", "timed out")) {
            return "timeout";
        }
        if (containsAnyIgnoreCase(normalized, "达到上限", "预算", "budget", "limit exceeded", "rate limit", "too many")) {
            return "budget_exhausted";
        }
        if (containsAnyIgnoreCase(normalized, "参数", "invalid argument", "invalid parameter", "bad request",
                "status code: 400", "http 400")) {
            return "invalid_arguments";
        }
        if (containsAnyIgnoreCase(normalized, "权限", "permission denied", "forbidden", "unauthorized")) {
            return "permission_denied";
        }
        if (containsAnyIgnoreCase(normalized, "中断", "interrupted", "cancelled", "canceled")) {
            return "interrupted";
        }
        if (containsAnyIgnoreCase(normalized, "不可用", "初始化", "unavailable", "not available", "connection refused")) {
            return "unavailable";
        }
        return "execution_failed";
    }

    private static String normalizeErrorCode(String errorCode, String message) {
        if (StringUtils.isNotBlank(errorCode)) {
            return errorCode.trim();
        }
        return detectErrorCode(message);
    }

    private static boolean containsAnyIgnoreCase(String value, String... patterns) {
        if (StringUtils.isBlank(value) || patterns == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern != null && lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String stripLeadingMarker(String value, String marker) {
        return StringUtils.defaultString(value).startsWith(marker)
                ? StringUtils.defaultString(value).substring(marker.length()).trim()
                : StringUtils.defaultString(value).trim();
    }

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String normalized = String.valueOf(value).trim();
            if (StringUtils.isNotBlank(normalized)) {
                return normalized;
            }
        }
        return "";
    }

    private static String trimToStructuredBoundary(String text, int limit) {
        if (StringUtils.isBlank(text) || text.length() <= limit) {
            return StringUtils.defaultString(text);
        }
        int minBoundary = Math.max(32, (int) Math.floor(limit * 0.6));
        int boundary = -1;
        String[] preferredTokens = text.trim().startsWith("{") || text.trim().startsWith("[")
                ? new String[] { "\n", "},", "],", "}", "]", "," }
                : new String[] { "\n\n", "\n", "。", "；", "！", "？", ". ", "; ", "，", "、", " " };
        for (String token : preferredTokens) {
            int candidate = text.lastIndexOf(token, limit - 1);
            if (candidate >= minBoundary) {
                boundary = Math.max(boundary, candidate + token.length());
                break;
            }
        }
        if (boundary <= 0) {
            boundary = limit;
        }
        return text.substring(0, safeSubstringEnd(text, Math.min(boundary, text.length()))).trim();
    }

    private static int safeSubstringEnd(String text, int endIndex) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        int safeEnd = Math.max(0, Math.min(endIndex, text.length()));
        if (safeEnd > 0 && safeEnd < text.length() && Character.isHighSurrogate(text.charAt(safeEnd - 1))
                && Character.isLowSurrogate(text.charAt(safeEnd))) {
            return safeEnd - 1;
        }
        return safeEnd;
    }

    public record ToolPayloadItem(String name, String arguments, String result, Integer durationMs) {
    }

    public record PendingToolPayloadItem(String name, String arguments) {
    }

    private record JsonCompactionProfile(int maxDepth, int maxObjectEntries, int maxArrayItems, int maxStringChars) {
    }

    public record ToolExecutionResult(boolean success, String errorCode, String message, String raw) {
    }

    private record PatternReplacement(Pattern pattern, String replacement) {
    }
}
