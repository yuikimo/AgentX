package com.example.agentx.application.conversation.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import org.springframework.stereotype.Component;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.ContextDomainService;
import com.example.agentx.domain.conversation.service.ConversationDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/** RAG会话管理器 负责管理RAG对话的临时会话，支持会话复用和自动清理 */
@Component
public class RagSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(RagSessionManager.class);

    /** 临时RAG会话缓存 - 过期后会清理底层临时会话 */
    private final Cache<String, String> temporarySessionCache;
    /** 显式绑定会话缓存 - 仅记录绑定关系，不清理底层正式会话 */
    private final Cache<String, String> boundSessionCache;

    /** RAG会话最大存活时间（分钟） */
    private static final int RAG_SESSION_MAX_LIFE_MINUTES = 30;
    public static final String RAG_AGENT_ID = "system-rag-agent";

    private final SessionDomainService sessionDomainService;
    private final ConversationDomainService conversationDomainService;
    private final ContextDomainService contextDomainService;

    public RagSessionManager(SessionDomainService sessionDomainService,
            ConversationDomainService conversationDomainService,
            ContextDomainService contextDomainService) {
        this.sessionDomainService = sessionDomainService;
        this.conversationDomainService = conversationDomainService;
        this.contextDomainService = contextDomainService;

        // 初始化Guava Cache，基于“最后活跃时间”30分钟自动过期
        this.temporarySessionCache = CacheBuilder.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(RAG_SESSION_MAX_LIFE_MINUTES)).maximumSize(1000) // 最大缓存1000个会话
                .removalListener(this::onSessionRemoved)
                .recordStats() // 启用统计功能
                .build();
        this.boundSessionCache = CacheBuilder.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(RAG_SESSION_MAX_LIFE_MINUTES))
                .maximumSize(1000)
                .recordStats()
                .build();
    }

    /** 为用户创建或获取RAG临时会话
     * @param userId 用户ID
     * @return 会话ID */
    public String createOrGetRagSession(String userId) {
        String existingSessionId = boundSessionCache.getIfPresent(userId);
        if (existingSessionId != null) {
            logger.debug("复用显式绑定的RAG会话: {} for user: {}", existingSessionId, userId);
            return existingSessionId;
        }
        existingSessionId = temporarySessionCache.getIfPresent(userId);

        // 如果缓存中存在会话（说明未过期），直接返回
        if (existingSessionId != null) {
            logger.debug("复用已存在的RAG会话: {} for user: {}", existingSessionId, userId);
            return existingSessionId;
        }

        // 创建新会话
        return createNewRagSession(userId);
    }

    /** 强制为用户创建新的RAG会话并覆盖缓存 */
    public String createNewRagSessionForUser(String userId) {
        return createNewRagSession(userId);
    }

    /** 为用户RAG对话创建新的临时会话
     * @param userId 用户ID
     * @param userRagId 用户RAG ID
     * @return 会话ID */
    public String createOrGetUserRagSession(String userId, String userRagId) {
        String sessionKey = userId + "_" + userRagId;
        String existingSessionId = boundSessionCache.getIfPresent(sessionKey);
        if (existingSessionId != null) {
            logger.debug("复用显式绑定的用户RAG会话: {} for user: {} userRag: {}", existingSessionId, userId, userRagId);
            return existingSessionId;
        }
        existingSessionId = temporarySessionCache.getIfPresent(sessionKey);

        // 如果缓存中存在会话（说明未过期），直接返回
        if (existingSessionId != null) {
            logger.debug("复用已存在的用户RAG会话: {} for user: {} userRag: {}", existingSessionId, userId, userRagId);
            return existingSessionId;
        }

        // 创建新会话
        return createNewUserRagSession(userId, userRagId, sessionKey);
    }

    /** 强制为用户知识库对话创建新的RAG会话并覆盖缓存 */
    public String createNewUserRagSessionForUser(String userId, String userRagId) {
        String sessionKey = userId + "_" + userRagId;
        return createNewUserRagSession(userId, userRagId, sessionKey);
    }

    /** 绑定显式会话到RAG缓存，便于后续请求按会话继续 */
    public void bindRagSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String existingSessionId = boundSessionCache.getIfPresent(userId);
        if (sessionId.equals(existingSessionId)) {
            return;
        }
        boundSessionCache.put(userId, sessionId);
    }

    /** 绑定显式会话到用户知识库RAG缓存 */
    public void bindUserRagSession(String userId, String userRagId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String sessionKey = userId + "_" + userRagId;
        String existingSessionId = boundSessionCache.getIfPresent(sessionKey);
        if (sessionId.equals(existingSessionId)) {
            return;
        }
        boundSessionCache.put(sessionKey, sessionId);
    }

    /** 创建新的RAG会话
     * @param userId 用户ID
     * @return 会话ID */
    private String createNewRagSession(String userId) {
        try {
            SessionEntity session = sessionDomainService.createSession(RAG_AGENT_ID, userId, "RAG对话", true);
            String sessionId = session.getId();

            // 缓存会话信息到Guava Cache（自动TTL管理）
            temporarySessionCache.put(userId, sessionId);

            logger.info("创建新的RAG会话: {} for user: {}", sessionId, userId);
            return sessionId;

        } catch (Exception e) {
            logger.error("创建RAG会话失败 for user: {}", userId, e);
            throw new RuntimeException("创建RAG会话失败", e);
        }
    }

    /** 创建新的用户RAG会话
     * @param userId 用户ID
     * @param userRagId 用户RAG ID
     * @param sessionKey 会话缓存键
     * @return 会话ID */
    private String createNewUserRagSession(String userId, String userRagId, String sessionKey) {
        try {
            SessionEntity session = sessionDomainService.createSession(RAG_AGENT_ID, userId, "知识库对话 - " + userRagId,
                    true);
            String sessionId = session.getId();

            // 缓存会话信息到Guava Cache（自动TTL管理）
            temporarySessionCache.put(sessionKey, sessionId);

            logger.info("创建新的用户RAG会话: {} for user: {} userRag: {}", sessionId, userId, userRagId);
            return sessionId;

        } catch (Exception e) {
            logger.error("创建用户RAG会话失败 for user: {} userRag: {}", userId, userRagId, e);
            throw new RuntimeException("创建用户RAG会话失败", e);
        }
    }

    /** 手动清理指定用户的RAG会话
     * @param userId 用户ID */
    public void clearUserRagSessions(String userId) {
        String sessionId = temporarySessionCache.getIfPresent(userId);
        if (sessionId != null) {
            temporarySessionCache.invalidate(userId);
            logger.info("手动清理用户RAG会话: {} for user: {}", sessionId, userId);
        }
        boundSessionCache.invalidate(userId);
    }

    /** 手动清理指定用户某个知识库的RAG会话 */
    public void clearUserRagSession(String userId, String userRagId) {
        String sessionKey = userId + "_" + userRagId;
        String sessionId = temporarySessionCache.getIfPresent(sessionKey);
        if (sessionId != null) {
            temporarySessionCache.invalidate(sessionKey);
            logger.info("手动清理用户知识库RAG会话: {} for user: {} userRag: {}", sessionId, userId, userRagId);
        }
        boundSessionCache.invalidate(sessionKey);
    }

    /** 关闭指定RAG会话（显式关闭弹窗时调用） */
    public void closeSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        temporarySessionCache.asMap().entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
        boundSessionCache.asMap().entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
        logger.info("显式关闭RAG会话: sessionId={}, userId={}", sessionId, userId);
    }

    /** 获取当前缓存的会话数量（用于监控） */
    public long getCachedSessionCount() {
        return temporarySessionCache.size() + boundSessionCache.size();
    }

    /** 获取缓存统计信息（可选，用于监控和调试） */
    public String getCacheStats() {
        return String.format("临时缓存大小: %d, 临时统计: %s, 绑定缓存大小: %d, 绑定统计: %s",
                temporarySessionCache.size(), temporarySessionCache.stats().toString(),
                boundSessionCache.size(), boundSessionCache.stats().toString());
    }

    /** 清理所有过期会话（手动触发清理） */
    public void cleanupExpiredSessions() {
        temporarySessionCache.cleanUp();
        boundSessionCache.cleanUp();
        logger.info("手动清理过期的RAG会话缓存");
    }

    /** 应用启动时清理遗留的临时RAG会话 */
    public void cleanupTemporarySessionsOnStartup() {
        try {
            List<String> sessionIds = sessionDomainService.listByAgentId(RAG_AGENT_ID).stream()
                    .map(SessionEntity::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toList());
            if (sessionIds.isEmpty()) {
                logger.info("启动清理RAG临时会话：无需清理");
                return;
            }

            conversationDomainService.deleteConversationMessages(sessionIds);
            contextDomainService.deleteBySessionIds(sessionIds);
            sessionDomainService.deleteSessions(sessionIds);
            temporarySessionCache.invalidateAll();
            boundSessionCache.invalidateAll();
            logger.info("启动清理RAG临时会话完成: count={}", sessionIds.size());
        } catch (Exception e) {
            logger.warn("启动清理RAG临时会话失败: {}", e.getMessage());
        }
    }

    private void onSessionRemoved(RemovalNotification<String, String> notification) {
        String sessionId = notification.getValue();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        RemovalCause cause = notification.getCause();
        if (cause == RemovalCause.REPLACED) {
            logger.debug("RAG会话缓存替换，准备清理旧会话: key={}, sessionId={}", notification.getKey(), sessionId);
        } else {
            logger.info("RAG会话缓存移除: key={}, sessionId={}, cause={}", notification.getKey(), sessionId, cause);
        }
        cleanupSessionData(sessionId);
    }

    private void cleanupSessionData(String sessionId) {
        try {
            SessionEntity session = sessionDomainService.findById(sessionId);
            if (session == null) {
                return;
            }
            if (!RAG_AGENT_ID.equals(session.getAgentId())) {
                logger.debug("跳过非RAG会话清理: sessionId={}, agentId={}", sessionId, session.getAgentId());
                return;
            }

            conversationDomainService.deleteConversationMessages(sessionId);
            contextDomainService.deleteBySessionId(sessionId);
            sessionDomainService.deleteSessionById(sessionId);
            logger.info("已清理RAG临时会话数据: sessionId={}", sessionId);
        } catch (Exception e) {
            logger.warn("清理RAG临时会话失败: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }
}
