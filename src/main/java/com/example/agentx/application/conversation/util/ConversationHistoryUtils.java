package com.example.agentx.application.conversation.util;

import org.apache.commons.lang3.StringUtils;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 会话历史辅助工具 */
public final class ConversationHistoryUtils {

    private ConversationHistoryUtils() {
    }

    @Nullable
    public static MessageEntity getSummaryFromHistory(List<MessageEntity> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return null;
        }
        return historyMessages.stream().filter(Objects::nonNull).filter(MessageEntity::isSummaryMessage).findFirst()
                .orElse(null);
    }

    public static List<MessageEntity> stripSummaryMessages(List<MessageEntity> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageEntity> filteredMessages = new ArrayList<>(historyMessages.size());
        for (MessageEntity historyMessage : historyMessages) {
            if (historyMessage != null && !historyMessage.isSummaryMessage()) {
                filteredMessages.add(historyMessage);
            }
        }
        return filteredMessages;
    }

    public static String resolveSummaryContent(List<MessageEntity> historyMessages, @Nullable ContextEntity contextEntity) {
        MessageEntity summaryMessage = getSummaryFromHistory(historyMessages);
        if (summaryMessage != null && StringUtils.isNotBlank(summaryMessage.getContent())) {
            return summaryMessage.getContent();
        }
        return contextEntity != null ? StringUtils.defaultString(contextEntity.getSummary()) : "";
    }

}
