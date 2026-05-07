package com.example.agentx.application.conversation.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.prompt.ConversationPromptTemplates;
import com.example.agentx.domain.prompt.PromptXmlUtils;

/** 对话 prompt 上下文辅助工具 */
public final class ConversationPromptContextUtils {

    private ConversationPromptContextUtils() {
    }

    public static String abbreviatePromptText(String value, int limit) {
        String normalized = StringUtils.defaultString(value).replace("\r", " ").replace("\n", " ").replace("\\n", " ")
                .trim().replaceAll("\\s+", " ");
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    public static String resolveNormalizedSummary(List<MessageEntity> messageHistory, ContextEntity contextEntity) {
        return ConversationPromptTemplates.normalizeConversationSummary(
                ConversationHistoryUtils.resolveSummaryContent(messageHistory, contextEntity));
    }

    public static List<String> buildRecentTextHistory(List<MessageEntity> messageHistory, int maxItems, int perItemLimit) {
        List<MessageEntity> nonSummaryHistory = ConversationHistoryUtils.stripSummaryMessages(messageHistory);
        if (nonSummaryHistory.isEmpty() || maxItems <= 0) {
            return Collections.emptyList();
        }
        List<String> recentItems = new ArrayList<>();
        for (int index = nonSummaryHistory.size() - 1; index >= 0 && recentItems.size() < maxItems; index--) {
            MessageEntity message = nonSummaryHistory.get(index);
            if (!shouldUseAsRecentTextHistory(message)) {
                continue;
            }
            String role = message.getRole() == Role.USER ? "user" : "assistant";
            recentItems.add(role + ": " + abbreviatePromptText(message.getContent(), perItemLimit));
        }
        Collections.reverse(recentItems);
        return recentItems;
    }

    public static String buildRewriteHistoryContext(List<MessageEntity> messageHistory, ContextEntity contextEntity,
            int maxTurns, int perItemLimit) {
        if ((messageHistory == null || messageHistory.isEmpty()) && contextEntity == null) {
            return "";
        }
        StringBuilder history = new StringBuilder();
        String summary = resolveNormalizedSummary(messageHistory, contextEntity);
        if (StringUtils.isNotBlank(summary)) {
            history.append("<conversation_summary>\n").append(PromptXmlUtils.escapeXml(summary))
                    .append("\n</conversation_summary>\n");
        }

        List<MessageEntity> recentMessages = ConversationHistoryUtils.stripSummaryMessages(messageHistory).stream()
                .filter(Objects::nonNull)
                .filter(ConversationPromptContextUtils::shouldUseAsRecentTextHistory)
                .sorted(Comparator.comparing(MessageEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int fromIndex = Math.max(0, recentMessages.size() - Math.max(0, maxTurns));
        List<MessageEntity> selectedMessages = recentMessages.subList(fromIndex, recentMessages.size());
        if (selectedMessages.isEmpty() && history.length() == 0) {
            return "";
        }

        history.append("<recent_turns>\n");
        for (MessageEntity messageEntity : selectedMessages) {
            String content = abbreviatePromptText(StringUtils.defaultString(messageEntity.getContent()).trim(),
                    perItemLimit);
            if (messageEntity.isUserMessage()) {
                history.append("<user>").append(PromptXmlUtils.escapeXml(content)).append("</user>\n");
            } else if (messageEntity.isAIMessage()) {
                history.append("<assistant>").append(PromptXmlUtils.escapeXml(content)).append("</assistant>\n");
            }
        }
        history.append("</recent_turns>");
        return history.toString();
    }

    private static boolean shouldUseAsRecentTextHistory(MessageEntity message) {
        if (message == null || message.isSummaryMessage() || StringUtils.isBlank(message.getContent())) {
            return false;
        }
        if (message.getRole() != Role.USER && message.getRole() != Role.ASSISTANT) {
            return false;
        }
        MessageType messageType = message.getMessageType();
        return messageType == null || messageType == MessageType.TEXT;
    }
}
