package com.example.agentx.domain.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import org.springframework.transaction.annotation.Transactional;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.repository.ContextRepository;
import com.example.agentx.domain.conversation.repository.MessageRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MessageDomainService {

    private final MessageRepository messageRepository;

    private final ContextRepository contextRepository;

    private final ChatContextProperties chatContextProperties;

    public MessageDomainService(MessageRepository messageRepository, ContextRepository contextRepository,
            ChatContextProperties chatContextProperties) {
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.chatContextProperties = chatContextProperties;
    }

    public List<MessageEntity> listByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<MessageEntity> rawMessages = messageRepository.selectByIds(ids);
        Map<String, MessageEntity> messageMap = new LinkedHashMap<>();
        for (MessageEntity rawMessage : rawMessages) {
            if (rawMessage != null && StringUtils.hasText(rawMessage.getId())) {
                messageMap.put(rawMessage.getId(), rawMessage);
            }
        }
        List<MessageEntity> orderedMessages = new ArrayList<>();
        for (String id : ids) {
            MessageEntity messageEntity = messageMap.get(id);
            if (messageEntity != null) {
                orderedMessages.add(messageEntity);
            }
        }
        return orderedMessages;
    }

    public MessageEntity getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return messageRepository.selectById(id);
    }

    public List<MessageEntity> listActiveMessages(ContextEntity contextEntity) {
        if (contextEntity == null || !StringUtils.hasText(contextEntity.getSessionId())) {
            return new ArrayList<>();
        }
        String windowStartMessageId = resolveWindowStartMessageId(contextEntity);
        if (StringUtils.hasText(windowStartMessageId)) {
            MessageEntity startMessage = messageRepository.selectById(windowStartMessageId);
            if (startMessage != null && Objects.equals(contextEntity.getSessionId(), startMessage.getSessionId())
                    && startMessage.getCreatedAt() != null) {
                return listWindowMessages(contextEntity.getSessionId(), startMessage.getCreatedAt());
            }
        }
        return listRecentMessages(contextEntity.getSessionId());
    }

    private List<MessageEntity> listWindowMessages(String sessionId, LocalDateTime windowStartTime) {
        int limit = resolveMaxActiveMessages();
        if (limit <= 0) {
            return messageRepository.selectList(Wrappers.<MessageEntity>lambdaQuery()
                    .eq(MessageEntity::getSessionId, sessionId)
                    .ge(MessageEntity::getCreatedAt, windowStartTime)
                    .orderByAsc(MessageEntity::getCreatedAt));
        }
        List<MessageEntity> messages = messageRepository.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getSessionId, sessionId)
                .ge(MessageEntity::getCreatedAt, windowStartTime)
                .orderByDesc(MessageEntity::getCreatedAt)
                .last("LIMIT " + limit));
        Collections.reverse(messages);
        return messages;
    }

    private List<MessageEntity> listRecentMessages(String sessionId) {
        int limit = resolveMaxActiveMessages();
        if (limit <= 0) {
            return messageRepository.selectList(Wrappers.<MessageEntity>lambdaQuery()
                    .eq(MessageEntity::getSessionId, sessionId)
                    .orderByAsc(MessageEntity::getCreatedAt));
        }
        List<MessageEntity> messages = messageRepository.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getSessionId, sessionId)
                .orderByDesc(MessageEntity::getCreatedAt)
                .last("LIMIT " + limit));
        Collections.reverse(messages);
        return messages;
    }

    private int resolveMaxActiveMessages() {
        return Math.max(0, chatContextProperties.getHistory().getMaxActiveMessages());
    }

    public List<MessageEntity> listSummaryAndRecentMessages(ContextEntity contextEntity, int recentMessageLimit) {
        if (contextEntity == null || !StringUtils.hasText(contextEntity.getSessionId()) || recentMessageLimit <= 0) {
            return new ArrayList<>();
        }
        String sessionId = contextEntity.getSessionId();
        List<MessageEntity> result = new ArrayList<>();

        MessageEntity summaryMessage = messageRepository.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getSessionId, sessionId)
                .eq(MessageEntity::getRole, com.example.agentx.domain.conversation.constant.Role.SUMMARY)
                .orderByDesc(MessageEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (summaryMessage != null) {
            result.add(summaryMessage);
        }

        List<MessageEntity> recentMessages = messageRepository.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getSessionId, sessionId)
                .ne(MessageEntity::getRole, com.example.agentx.domain.conversation.constant.Role.SUMMARY)
                .orderByDesc(MessageEntity::getCreatedAt)
                .last("LIMIT " + recentMessageLimit));
        if (recentMessages.isEmpty()) {
            return result;
        }
        Collections.reverse(recentMessages);
        result.addAll(recentMessages);
        return result;
    }

    /** 保存消息并且更新消息到上下文 */
    @Transactional(rollbackFor = Exception.class)
    public void saveMessageAndUpdateContext(List<MessageEntity> messageEntities, ContextEntity contextEntity) {
        if (messageEntities == null || messageEntities.isEmpty()) {
            return;
        }
        String windowStartMessageId = resolveWindowStartMessageId(contextEntity, messageEntities);
        for (MessageEntity messageEntity : messageEntities) {
            messageEntity.setId(null);
            messageEntity.setCreatedAt(LocalDateTime.now());
        }
        messageRepository.insert(messageEntities);
        if (!StringUtils.hasText(windowStartMessageId)) {
            windowStartMessageId = firstMessageId(messageEntities);
        }
        contextEntity.setActiveWindowStartMessageId(windowStartMessageId);
        contextEntity.setActiveMessages(null);
        contextRepository.insertOrUpdate(contextEntity);
    }

    /** 保存消息 */
    public void saveMessage(List<MessageEntity> messageEntities) {
        if (messageEntities == null || messageEntities.isEmpty()) {
            return;
        }
        for (MessageEntity messageEntity : messageEntities) {
            if (messageEntity.getCreatedAt() == null) {
                messageEntity.setCreatedAt(LocalDateTime.now());
            }
        }
        messageRepository.insert(messageEntities);
    }

    public void updateMessage(MessageEntity message) {
        messageRepository.updateById(message);
    }

    public void updateMessages(List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (MessageEntity message : messages) {
            if (message == null || message.getId() == null) {
                continue;
            }
            messageRepository.updateById(message);
        }
    }

    public void updateMessageTokenCounts(List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<MessageEntity> validMessages = new ArrayList<>();
        for (MessageEntity message : messages) {
            if (message == null || !StringUtils.hasText(message.getId())) {
                continue;
            }
            validMessages.add(message);
        }
        if (validMessages.isEmpty()) {
            return;
        }
        messageRepository.batchUpdateTokenCounts(validMessages);
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeTurn(ContextEntity contextEntity, List<MessageEntity> updatedMessages,
            List<MessageEntity> insertedMessages) {
        updateMessages(updatedMessages == null ? Collections.emptyList() : updatedMessages);
        saveMessage(insertedMessages == null ? Collections.emptyList() : insertedMessages);
        if (contextEntity != null) {
            String windowStartMessageId = resolveWindowStartMessageId(contextEntity, updatedMessages, insertedMessages);
            contextEntity.setActiveWindowStartMessageId(windowStartMessageId);
            contextEntity.setActiveMessages(null);
            contextRepository.insertOrUpdate(contextEntity);
        }
    }

    public boolean isFirstConversation(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        Long userMessageCount = messageRepository.selectCount(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getSessionId, sessionId)
                .eq(MessageEntity::getRole, Role.USER));
        return userMessageCount != null && userMessageCount <= 1;
    }

    private String resolveWindowStartMessageId(ContextEntity contextEntity, List<MessageEntity>... candidates) {
        if (contextEntity != null && contextEntity.hasActiveWindowStartMessageId()) {
            return contextEntity.getActiveWindowStartMessageId();
        }
        String legacyWindowStartMessageId = resolveLegacyWindowStartMessageId(contextEntity);
        if (StringUtils.hasText(legacyWindowStartMessageId)) {
            return legacyWindowStartMessageId;
        }
        if (candidates != null) {
            for (List<MessageEntity> candidateMessages : candidates) {
                String firstMessageId = firstMessageId(candidateMessages);
                if (StringUtils.hasText(firstMessageId)) {
                    return firstMessageId;
                }
            }
        }
        return null;
    }

    private String resolveLegacyWindowStartMessageId(ContextEntity contextEntity) {
        if (contextEntity == null || contextEntity.getActiveMessages() == null) {
            return null;
        }
        for (String activeMessageId : contextEntity.getActiveMessages()) {
            if (StringUtils.hasText(activeMessageId)) {
                return activeMessageId;
            }
        }
        return null;
    }

    private String firstMessageId(List<MessageEntity> messageEntities) {
        if (messageEntities == null) {
            return null;
        }
        for (MessageEntity messageEntity : messageEntities) {
            if (messageEntity != null && StringUtils.hasText(messageEntity.getId())) {
                return messageEntity.getId();
            }
        }
        return null;
    }
}
