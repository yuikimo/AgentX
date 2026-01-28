package com.example.agentx.domain.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.repository.ContextRepository;
import com.example.agentx.domain.conversation.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话服务实现
 */
@Service
public class ConversationDomainService {

    private final Logger logger = LoggerFactory.getLogger(ConversationDomainService.class);
    private final MessageRepository messageRepository;
    private final ContextRepository contextRepository;
    private final SessionDomainService sessionDomainService;


    public ConversationDomainService(MessageRepository messageRepository,
                                     ContextRepository contextRepository,
                                     SessionDomainService sessionDomainService) {
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.sessionDomainService = sessionDomainService;
    }

    /**
     * 获取会话中的消息列表
     *
     * @param sessionId 会话id
     * @return 消息列表
     */
    public List<MessageEntity> getConversationMessages(String sessionId) {
        LambdaQueryWrapper<MessageEntity> queryWrapper =
                Wrappers.<MessageEntity>lambdaQuery().eq(MessageEntity::getSessionId, sessionId);
        return messageRepository.selectList(queryWrapper);
    }

    public void insertBathMessage(List<MessageEntity> messages) {
        messageRepository.insert(messages);
    }

    public MessageEntity saveMessage(MessageEntity message) {
        messageRepository.insert(message);
        return message;
    }

    /**
     * 更新上下文，添加新消息到活跃消息列表
     *
     * @param sessionId 会话id
     * @param messageId 消息id
     */
    private void updateContext(String sessionId, String messageId) {
        LambdaQueryWrapper<ContextEntity> queryWrapper =
                Wrappers.<ContextEntity>lambdaQuery().eq(ContextEntity::getSessionId, sessionId);
        // 查找当前会话的上下文
        ContextEntity context = contextRepository.selectOne(queryWrapper);

        // 如果上下文不存在，创建新上下文
        if (context == null) {
            context = ContextEntity.createNew(sessionId);
            context.addMessage(messageId);
            contextRepository.insert(context);
        } else {
            // 更新现有上下文
            context.addMessage(messageId);
            context.setUpdatedAt(LocalDateTime.now());
            contextRepository.updateById(context);
        }
    }

    /**
     * 删除会话下的消息
     *
     * @param sessionId 会话id
     */
    public void deleteConversationMessages(String sessionId) {
        LambdaQueryWrapper<MessageEntity> queryWrapper =
                Wrappers.<MessageEntity>lambdaQuery().eq(MessageEntity::getSessionId, sessionId);
        messageRepository.checkedDelete(queryWrapper);
    }

    public void deleteConversationMessages(List<String> sessionIds) {
        LambdaQueryWrapper<MessageEntity> queryWrapper =
                Wrappers.<MessageEntity>lambdaQuery().in(MessageEntity::getSessionId, sessionIds);
        messageRepository.checkedDelete(queryWrapper);
    }

    /**
     * 更新消息的token数量
     *
     * @param message 消息实体
     */
    @Transactional
    public void updateMessageTokenCount(MessageEntity message) {
        logger.info("更新消息token数量，消息ID: {}, token数量: {}", message.getId(), message.getTokenCount());
        messageRepository.checkedUpdateById(message);
    }
}
