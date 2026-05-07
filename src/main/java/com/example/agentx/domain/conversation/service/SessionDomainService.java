package com.example.agentx.domain.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.repository.SessionRepository;
import com.example.agentx.infrastructure.exception.BusinessException;

import java.util.List;

@Service
public class SessionDomainService {

    private final SessionRepository sessionRepository;

    public SessionDomainService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** 根据 agentId 获取会话列表
     * 
     * @param agentId 助理id */
    public List<SessionEntity> getSessionsByAgentId(String agentId, String userId) {
        return sessionRepository.selectList(Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getAgentId, agentId)
                .eq(SessionEntity::getUserId, userId).orderByDesc(SessionEntity::getCreatedAt));
    }

    /** 删除会话
     * 
     * @param sessionId 会话id
     * @param userId 用户id */
    public void deleteSession(String sessionId, String userId) {
        sessionRepository.checkedDelete(Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getId, sessionId)
                .eq(SessionEntity::getUserId, userId));
    }

    /** 更新会话
     * 
     * @param sessionId 会话id
     * @param userId 用户id
     * @param title 标题 */
    public void updateSession(String sessionId, String userId, String title) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTitle(title);
        session.setTitleRenamed(true);
        sessionRepository.checkedUpdate(session, Wrappers.<SessionEntity>lambdaUpdate()
                .eq(SessionEntity::getId, sessionId).eq(SessionEntity::getUserId, userId));
    }

    /** 创建会话
     * 
     * @param agentId 助理id
     * @param userId 用户id */
    public SessionEntity createSession(String agentId, String userId) {
        return createSession(agentId, userId, SessionEntity.DEFAULT_TITLE, false);
    }

    public SessionEntity createSession(String agentId, String userId, String title, boolean titleRenamed) {
        SessionEntity session = new SessionEntity();
        session.setAgentId(agentId);
        session.setUserId(userId);
        session.setTitle(title);
        session.setTitleRenamed(titleRenamed);
        sessionRepository.insert(session);
        return session;
    }

    /** 检查会话是否存在
     * 
     * @param sessionId 会话id
     * @param userId 用户id */
    public void checkSessionExist(String sessionId, String userId) {
        SessionEntity session = sessionRepository.selectOne(Wrappers.<SessionEntity>lambdaQuery()
                .eq(SessionEntity::getId, sessionId).eq(SessionEntity::getUserId, userId));
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
    }

    public SessionEntity find(String sessionId, String userId) {
        return sessionRepository.selectOne(Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getId, sessionId)
                .eq(SessionEntity::getUserId, userId));
    }

    public void deleteSessions(List<String> sessionIds) {
        sessionRepository.delete(Wrappers.<SessionEntity>lambdaQuery().in(SessionEntity::getId, sessionIds));
    }

    public List<SessionEntity> listByAgentId(String agentId) {
        return sessionRepository.selectList(Wrappers.<SessionEntity>lambdaQuery()
                .eq(SessionEntity::getAgentId, agentId));
    }

    public SessionEntity getSession(String sessionId, String userId) {
        SessionEntity session = sessionRepository.selectOne(Wrappers.<SessionEntity>lambdaQuery()
                .eq(SessionEntity::getId, sessionId).eq(SessionEntity::getUserId, userId));
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        return session;
    }

    public SessionEntity findById(String sessionId) {
        return sessionRepository.selectOne(Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getId, sessionId));
    }

    public boolean shouldAutoRename(String sessionId, String userId) {
        SessionEntity session = sessionRepository.selectOne(Wrappers.<SessionEntity>lambdaQuery()
                .select(SessionEntity::getId, SessionEntity::getTitle, SessionEntity::getTitleRenamed)
                .eq(SessionEntity::getId, sessionId).eq(SessionEntity::getUserId, userId));
        if (session == null || session.isTitleRenamed()) {
            return false;
        }
        return SessionEntity.DEFAULT_TITLE.equals(SessionEntity.normalizeTitle(session.getTitle()));
    }

    public void deleteSessionById(String sessionId) {
        sessionRepository.delete(Wrappers.<SessionEntity>lambdaQuery().eq(SessionEntity::getId, sessionId));
    }

}
