package com.example.agentx.domain.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.repository.SessionRepository;
import com.example.agentx.infrastructure.exception.BusinessException;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;

@Service
public class SessionDomainService {

    private final SessionRepository sessionRepository;

    public SessionDomainService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 根据 agentId 获取会话列表
     *
     * @param agentId 助理id
     */
    public List<SessionEntity> getSessionsByAgentId(String agentId) {
        LambdaQueryWrapper<SessionEntity> queryWrapper =
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getAgentId, agentId)
                        .orderByDesc(SessionEntity::getCreatedAt);
        return sessionRepository.selectList(queryWrapper);
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话id
     * @param userId    用户id
     */
    public void deleteSession(String sessionId, String userId) {
        LambdaQueryWrapper<SessionEntity> queryWrapper =
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getId, sessionId)
                        .eq(SessionEntity::getUserId, userId);
        sessionRepository.checkedDelete(queryWrapper);
    }

    /**
     * 更新会话
     *
     * @param sessionId 会话id
     * @param userId    用户id
     * @param title     标题
     */
    public void updateSession(String sessionId, String userId, String title) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTitle(title);

        LambdaUpdateWrapper<SessionEntity> updateWrapper =
                Wrappers.<SessionEntity>lambdaUpdate()
                        .eq(SessionEntity::getId, sessionId)
                        .eq(SessionEntity::getUserId, userId);
        sessionRepository.checkedUpdate(session, updateWrapper);
    }

    /**
     * 创建会话
     *
     * @param agentId 助理id
     * @param userId  用户id
     */
    public SessionEntity createSession(String agentId, String userId) {
        SessionEntity session = new SessionEntity();
        session.setAgentId(agentId);
        session.setUserId(userId);
        session.setTitle("新会话");
        sessionRepository.insert(session);
        return session;
    }

    /**
     * 检查会话是否存在
     *
     * @param sessionId 会话id
     * @param userId    用户id
     */
    public void checkSessionExist(String sessionId, String userId) {
        LambdaQueryWrapper<SessionEntity> queryWrapper =
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getId, sessionId)
                        .eq(SessionEntity::getUserId, userId);
        SessionEntity session = sessionRepository.selectOne(queryWrapper);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
    }

    public SessionEntity find(String sessionId, String userId) {
        LambdaQueryWrapper<SessionEntity> queryWrapper =
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getId, sessionId)
                        .eq(SessionEntity::getUserId, userId);
        return sessionRepository.selectOne(queryWrapper);
    }

    public void deleteSessions(List<String> sessionIds) {
        LambdaQueryWrapper<SessionEntity> queryWrapper =
                Wrappers.<SessionEntity>lambdaQuery().in(SessionEntity::getId, sessionIds);
        sessionRepository.delete(queryWrapper);
    }

    public SessionEntity getSession(String sessionId, String userId) {
        LambdaQueryWrapper<SessionEntity> queryWrapper =
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getId, sessionId)
                        .eq(SessionEntity::getUserId, userId);
        SessionEntity session = sessionRepository.selectOne(queryWrapper);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        return session;
    }
}
