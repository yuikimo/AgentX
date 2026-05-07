package com.example.agentx.domain.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class MemoryContentSupport {

    private static final Pattern QUOTE_EDGE_PATTERN = Pattern.compile("^[\"“”'‘’`]+|[\"“”'‘’`]+$");
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("(?i)[a-z]:\\\\[^\\s]+");
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile("(?<!\\w)/(?:[\\w.-]+/){1,}[\\w.-]*");
    private static final Pattern COMMAND_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:^|\\s)(?:ls|cat|cd|pwd|grep|find|curl|wget|rm|mv|cp|git|npm|pnpm|yarn|mvn|java|python|bash|powershell|cmd)(?:\\s|$)");
    private static final Pattern OPERATIONAL_PREFIX_PATTERN = Pattern.compile(
            "(?i)^(?:请|帮我|现在|继续|再次|然后|需要)?\\s*(?:调用|执行|运行|打开|查看|列出|搜索|检索|浏览|下载|上传|创建|修改|删除|读取|read|open|run|execute|browse|search|list|download|upload|delete|create|edit)(?:\\s|:|：|/|$)");
    private static final Pattern META_DIALOGUE_PATTERN = Pattern.compile(
            "(?i)^(?:用户|assistant|ai|本轮|当前对话|这次对话).{0,20}(?:询问|提到|要求|回复|回答|讨论|让我|ask|asked|request|requested|reply|replied|discuss)");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|passwd|jwt|bearer|access[_-]?key|private[_-]?key|ssh-rsa)");

    private static final List<String> ACTION_KEYWORDS = List.of("调用", "执行", "运行", "打开", "查看", "列出", "搜索", "检索", "浏览",
            "下载", "上传", "创建", "修改", "删除", "读取", "read", "open", "run", "execute", "browse", "search", "list",
            "download", "upload", "delete", "create", "edit");
    private static final List<String> TOOL_CONTEXT_KEYWORDS = List.of("工具调用", "调用工具", "函数调用", "function call", "子 agent",
            "tool result", "tool output", "终端", "shell", "命令行", "文件系统", "workspace", "目录", "路径");

    private MemoryContentSupport() {
    }

    static String sanitizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String sanitized = QUOTE_EDGE_PATTERN.matcher(text.trim()).replaceAll("");
        return sanitized.replaceAll("\\s+", " ").trim();
    }

    static List<String> sanitizeTags(List<String> tags, int maxTags) {
        if (tags == null || tags.isEmpty()) {
            return new ArrayList<>();
        }
        int limit = Math.max(1, maxTags);
        Set<String> normalizedTags = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            normalizedTags.add(tag.trim().toLowerCase(Locale.ROOT));
            if (normalizedTags.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(normalizedTags);
    }

    static boolean isLowInformationText(String text, int minCodePoints) {
        return codePointLength(text) < Math.max(1, minCodePoints);
    }

    static boolean isLikelySensitiveText(String text) {
        return StringUtils.hasText(text) && SENSITIVE_PATTERN.matcher(text).find();
    }

    static boolean isLikelyOperationalText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (OPERATIONAL_PREFIX_PATTERN.matcher(normalized).find()) {
            return true;
        }
        boolean hasPath = WINDOWS_PATH_PATTERN.matcher(normalized).find() || UNIX_PATH_PATTERN.matcher(normalized).find();
        if (hasPath && COMMAND_TOKEN_PATTERN.matcher(lower).find()) {
            return true;
        }
        return containsAny(lower, TOOL_CONTEXT_KEYWORDS) && containsAny(lower, ACTION_KEYWORDS);
    }

    static boolean isLikelyMetaDialogueText(String text) {
        return StringUtils.hasText(text) && META_DIALOGUE_PATTERN.matcher(text.trim()).find();
    }

    static String normalizeDedupeKey(String text) {
        String sanitized = sanitizeText(text).toLowerCase(Locale.ROOT);
        return sanitized.replaceAll("[\\p{Punct}，。！？；：“”‘’（）【】、《》]+", " ").replaceAll("\\s+", " ").trim();
    }

    static int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
