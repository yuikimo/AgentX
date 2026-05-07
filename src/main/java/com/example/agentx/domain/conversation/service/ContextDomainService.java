package com.example.agentx.domain.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.repository.ContextRepository;
import com.example.agentx.infrastructure.exception.BusinessException;

import java.util.ArrayList;
import java.util.Collection;

@Service
public class ContextDomainService {

    private static final Logger logger = LoggerFactory.getLogger(ContextDomainService.class);

    private final ContextRepository contextRepository;

    public ContextDomainService(ContextRepository contextRepository) {
        this.contextRepository = contextRepository;
    }

    // 获取历史消息id
    public ContextEntity getBySessionId(String sessionId) {
        LambdaQueryWrapper<ContextEntity> wrapper = Wrappers.<ContextEntity>lambdaQuery()
                .eq(ContextEntity::getSessionId, sessionId).select();
        ContextEntity contextEntity = contextRepository.selectOne(wrapper);
        if (contextEntity == null) {
            throw new BusinessException("消息上下文不存在");
        }
        return contextEntity;
    }

    public ContextEntity findBySessionId(String sessionId) {
        LambdaQueryWrapper<ContextEntity> wrapper = Wrappers.<ContextEntity>lambdaQuery()
                .eq(ContextEntity::getSessionId, sessionId);
        return contextRepository.selectOne(wrapper);
    }

    public ContextEntity insertOrUpdate(ContextEntity contextEntity) {
        try {
            contextRepository.insertOrUpdate(contextEntity);
        } catch (Exception e) {
            logger.error("保存会话上下文失败: sessionId={}, contextId={}",
                    contextEntity != null ? contextEntity.getSessionId() : null,
                    contextEntity != null ? contextEntity.getId() : null, e);
            throw new BusinessException("保存消息上下文失败", e);
        }
        return contextEntity;
    }

    public void deleteBySessionId(String sessionId) {
        contextRepository.delete(Wrappers.<ContextEntity>lambdaQuery().eq(ContextEntity::getSessionId, sessionId));
    }

    public void deleteBySessionIds(Iterable<String> sessionIds) {
        Collection<String> sessionIdCollection = new ArrayList<>();
        if (sessionIds != null) {
            sessionIds.forEach(sessionIdCollection::add);
        }
        if (CollectionUtils.isEmpty(sessionIdCollection)) {
            return;
        }
        contextRepository.delete(
                Wrappers.<ContextEntity>lambdaQuery().in(ContextEntity::getSessionId, sessionIdCollection));
    }
}
