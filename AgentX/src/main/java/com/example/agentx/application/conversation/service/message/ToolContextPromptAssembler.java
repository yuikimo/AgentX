package com.example.agentx.application.conversation.service.message;

import cn.hutool.core.collection.CollectionUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.util.ConversationPromptContextUtils;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.prompt.ConversationPromptTemplates;
import com.example.agentx.domain.prompt.PromptXmlUtils;
import com.example.agentx.domain.token.service.TokenEstimatorService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolContextPromptAssembler {
    private static final int MIN_RECENT_CONTEXT_BUDGET_TOKENS = 120;
    private static final int DEFAULT_RECENT_CONTEXT_BUDGET_TOKENS = 600;
    private static final int DEFAULT_RECENT_CONTEXT_MAX_ITEMS = 16;

    private final TokenEstimatorService tokenEstimatorService;
    private final ProviderConfigFactory providerConfigFactory;
    private final ChatToolProperties chatToolProperties;

    public ToolContextPromptAssembler(TokenEstimatorService tokenEstimatorService,
            ProviderConfigFactory providerConfigFactory, ChatToolProperties chatToolProperties) {
        this.tokenEstimatorService = tokenEstimatorService;
        this.providerConfigFactory = providerConfigFactory;
        this.chatToolProperties = chatToolProperties;
    }

    public String buildRecentToolContextSection(ChatContext chatContext) {
        return buildRecentToolContextSection(chatContext, chatContext != null ? chatContext.getMessageHistory() : null);
    }

    public String buildRecentToolContextSection(ChatContext chatContext, List<MessageEntity> messageHistory) {
        if (CollectionUtil.isEmpty(messageHistory)) {
            return "";
        }
        int budgetTokens = resolveRecentContextBudgetTokens();
        int maxItems = resolveRecentContextMaxItems();
        ProviderConfig providerConfig = buildProviderConfig(chatContext);
        LinkedHashMap<String, ToolContextItem> recentItems = new LinkedHashMap<>();
        int usedTokens = 0;
        for (int index = messageHistory.size() - 1; index >= 0 && recentItems.size() < maxItems; index--) {
            MessageEntity message = messageHistory.get(index);
            if (message == null) {
                continue;
            }
            ToolContextItem candidate = null;
            if (message.getMessageType() == MessageType.TOOL_CALL) {
                String summary = summarizeToolMessage(message);
                if (StringUtils.isNotBlank(summary)) {
                    candidate = new ToolContextItem("tool:" + buildToolSummaryKey(message, summary),
                            "- " + escapeXml(summary));
                }
            } else if (message.getMessageType() == MessageType.TOOL_NOTICE
                    && StringUtils.isNotBlank(message.getContent())) {
                String normalizedNotice = "工具状态提示：" + abbreviatePromptText(message.getContent(), 180);
                candidate = new ToolContextItem("notice:" + normalizedNotice, "- " + escapeXml(normalizedNotice));
            }
            if (candidate == null || StringUtils.isBlank(candidate.value())) {
                continue;
            }
            int candidateTokens = estimateTokens(candidate.value(), providerConfig);
            if (!recentItems.isEmpty() && usedTokens + candidateTokens > budgetTokens) {
                continue;
            }
            ToolContextItem removed = rememberRecentToolItem(recentItems, candidate, maxItems);
            if (removed != null) {
                usedTokens -= estimateTokens(removed.value(), providerConfig);
            }
            usedTokens += candidateTokens;
        }
        if (recentItems.isEmpty()) {
            return "";
        }
        List<String> orderedItems = new ArrayList<>(recentItems.values().stream().map(ToolContextItem::value).toList());
        Collections.reverse(orderedItems);
        return ConversationPromptTemplates.wrapRecentToolContext(String.join("\n", orderedItems));
    }

    private ToolContextItem rememberRecentToolItem(Map<String, ToolContextItem> recentItems, ToolContextItem item,
            int maxItems) {
        ToolContextItem removed = null;
        if (recentItems.containsKey(item.key())) {
            removed = recentItems.remove(item.key());
        }
        recentItems.put(item.key(), item);
        while (recentItems.size() > maxItems) {
            String oldestKey = recentItems.keySet().iterator().next();
            ToolContextItem oldest = recentItems.remove(oldestKey);
            if (removed == null) {
                removed = oldest;
            }
        }
        return removed;
    }

    private String buildToolSummaryKey(MessageEntity message, String summary) {
        Map<String, Object> payload = JsonUtils.parseMap(message != null ? message.getMetadata() : null);
        if (payload == null || payload.isEmpty()) {
            return StringUtils.defaultIfBlank(summary, "tool");
        }
        Object toolCallsObject = payload.get("toolCalls");
        if (toolCallsObject instanceof List<?> toolCalls && !toolCalls.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (Object item : toolCalls) {
                if (!(item instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                String toolName = rawItem.get("name") != null ? String.valueOf(rawItem.get("name")) : "unknown";
                String arguments = rawItem.get("arguments") != null ? String.valueOf(rawItem.get("arguments")) : "";
                String result = rawItem.get("result") != null ? String.valueOf(rawItem.get("result")) : "";
                String errorCategory = rawItem.get("errorCategory") != null ? String.valueOf(rawItem.get("errorCategory"))
                        : ToolPayloadUtils.classifyToolError(result);
                boolean success = resolveToolSuccess(rawItem.get("success"), result);
                keys.add(toolName + "|" + success + "|" + errorCategory + "|"
                        + Integer.toHexString((arguments + "::" + result).hashCode()));
            }
            if (!keys.isEmpty()) {
                return String.join(";", keys);
            }
        }
        String arguments = payload.get("arguments") != null ? String.valueOf(payload.get("arguments")) : "";
        String result = payload.get("result") != null ? String.valueOf(payload.get("result")) : "";
        String errorCategory = payload.get("errorCategory") != null ? String.valueOf(payload.get("errorCategory"))
                : ToolPayloadUtils.classifyToolError(result);
        return extractToolNameFromMessage(message) + "|" + resolveToolSuccess(payload.get("success"), result) + "|"
                + errorCategory + "|" + Integer.toHexString((arguments + "::" + result).hashCode());
    }

    private String summarizeToolMessage(MessageEntity message) {
        if (message == null) {
            return "";
        }
        Map<String, Object> payload = JsonUtils.parseMap(message.getMetadata());
        if (payload == null || payload.isEmpty()) {
            String toolName = extractToolNameFromMessage(message);
            return StringUtils.isNotBlank(toolName) ? toolName + "：已执行"
                    : abbreviatePromptText(message.getContent(), 120);
        }
        Object toolCallsObject = payload.get("toolCalls");
        if (toolCallsObject instanceof List<?> toolCalls && !toolCalls.isEmpty()) {
            List<String> summaries = new ArrayList<>();
            for (Object item : toolCalls) {
                if (!(item instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                String toolName = rawItem.get("name") != null ? String.valueOf(rawItem.get("name")) : "unknown";
                String result = rawItem.get("result") != null ? String.valueOf(rawItem.get("result")) : "";
                boolean success = resolveToolSuccess(rawItem.get("success"), result);
                String resultSummary = StringUtils.isNotBlank(result)
                        ? "，结果摘要=" + abbreviatePromptText(result, 120)
                        : "";
                summaries.add(toolName + "（" + (success ? "成功" : "失败") + resultSummary + "）");
                if (summaries.size() >= 3) {
                    break;
                }
            }
            return String.join("、", summaries);
        }
        String toolName = extractToolNameFromMessage(message);
        String result = payload.get("result") != null ? String.valueOf(payload.get("result")) : "";
        boolean success = resolveToolSuccess(payload.get("success"), result);
        StringBuilder summary = new StringBuilder();
        summary.append(StringUtils.defaultIfBlank(toolName, "工具调用")).append("：").append(success ? "成功" : "失败");
        Object durationMs = payload.get("durationMs");
        if (durationMs != null) {
            summary.append("，耗时").append(durationMs).append("ms");
        }
        if (StringUtils.isNotBlank(result)) {
            summary.append("；结果摘要=").append(abbreviatePromptText(result, 140));
        }
        return summary.toString();
    }

    private boolean resolveToolSuccess(Object successValue, String result) {
        if (successValue instanceof Boolean success) {
            return success;
        }
        return ToolPayloadUtils.isToolExecutionSuccessful(result);
    }

    private String extractToolNameFromMessage(MessageEntity message) {
        if (message == null || StringUtils.isBlank(message.getContent())) {
            return "";
        }
        String prefix = "执行工具：";
        String content = message.getContent().trim();
        return content.startsWith(prefix) ? content.substring(prefix.length()).trim() : content;
    }

    private String abbreviatePromptText(String value, int limit) {
        return ConversationPromptContextUtils.abbreviatePromptText(value, limit);
    }

    private String escapeXml(String text) {
        return PromptXmlUtils.escapeXml(text);
    }

    private int resolveRecentContextBudgetTokens() {
        int configured = chatToolProperties != null ? chatToolProperties.getRecentContextBudgetTokens()
                : DEFAULT_RECENT_CONTEXT_BUDGET_TOKENS;
        return Math.max(MIN_RECENT_CONTEXT_BUDGET_TOKENS, configured);
    }

    private int resolveRecentContextMaxItems() {
        int configured = chatToolProperties != null ? chatToolProperties.getRecentContextMaxItems()
                : DEFAULT_RECENT_CONTEXT_MAX_ITEMS;
        return Math.max(1, configured);
    }

    private int estimateTokens(String content, ProviderConfig providerConfig) {
        return tokenEstimatorService.estimateTextTokenCount(content, providerConfig);
    }

    private ProviderConfig buildProviderConfig(ChatContext chatContext) {
        if (chatContext == null) {
            return null;
        }
        ProviderConfig cached = chatContext.getResolvedProviderConfig();
        if (cached != null) {
            return cached;
        }
        ProviderConfig resolved = providerConfigFactory.fromChatContext(chatContext);
        chatContext.setResolvedProviderConfig(resolved);
        return resolved;
    }

    private record ToolContextItem(String key, String value) {
    }
}
