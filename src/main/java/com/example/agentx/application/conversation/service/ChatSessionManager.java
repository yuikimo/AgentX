package com.example.agentx.application.conversation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.config.ChatSessionProperties;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.infrastructure.transport.SseEmitterUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 聊天会话管理器 负责管理正在进行的对话会话，支持会话中断功能 */
@Component
public class ChatSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionManager.class);

    private static final String TIMEOUT_MESSAGE = "\n\n[系统提示：响应超时，请重试]";

    private final ChatSessionProperties chatSessionProperties;

    public ChatSessionManager(ChatSessionProperties chatSessionProperties) {
        this.chatSessionProperties = chatSessionProperties;
    }

    public enum RegistrationStatus {
        REGISTERED,
        NOT_REQUIRED,
        DUPLICATE_REJECTED
    }

    /** 会话信息 */
    public static class SessionInfo {
        private final String sessionId;
        private final String turnId;
        private final SseEmitter emitter;
        private final AtomicBoolean interrupted;
        private final long startTime;

        public SessionInfo(String sessionId, String turnId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.emitter = emitter;
            this.interrupted = new AtomicBoolean(false);
            this.startTime = System.currentTimeMillis();
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getTurnId() {
            return turnId;
        }

        public SseEmitter getEmitter() {
            return emitter;
        }

        public boolean isInterrupted() {
            return interrupted.get();
        }

        public void setInterrupted() {
            interrupted.set(true);
        }

        public long getStartTime() {
            return startTime;
        }
    }

    // 使用sessionId作为key，存储正在进行的对话会话
    private final ConcurrentHashMap<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> interruptedSessions = new ConcurrentHashMap<>();

    /** 注册一个新的对话会话
     * @param sessionId 会话ID
     * @param turnId 当前请求轮次ID
     * @param emitter SSE发送器 */
    public RegistrationStatus registerSession(String sessionId, String turnId, SseEmitter emitter) {
        if (sessionId == null || emitter == null) {
            return RegistrationStatus.NOT_REQUIRED;
        }
        SessionInfo sessionInfo = new SessionInfo(sessionId, turnId, emitter);
        SessionInfo previous = activeSessions.put(sessionId, sessionInfo);
        if (previous != null) {
            logger.warn("检测到重复注册，断开旧连接并接管新连接: sessionId={}, previousTurnId={}, newTurnId={}",
                    sessionId, previous.getTurnId(), turnId);
            interruptSession(previous, "新请求已接管当前会话");
        }
        logger.info("注册对话会话: sessionId={}, turnId={}", sessionId, turnId);

        // 设置SSE完成和超时回调，自动清理会话
        emitter.onCompletion(() -> {
            removeSession(sessionId, sessionInfo, "completion");
            logger.info("对话会话完成: sessionId={}", sessionId);
        });

        emitter.onTimeout(() -> {
            removeSession(sessionId, sessionInfo, "timeout");
            logger.warn("对话会话超时: sessionId={}", sessionId);
            AgentChatResponse response = ChatErrorResponseFactory.buildTimeout();
            SseEmitterUtils.safeSend(emitter, response);
            SseEmitterUtils.safeComplete(emitter);
        });

        emitter.onError((throwable) -> {
            removeSession(sessionId, sessionInfo, "error");
            logger.error("对话会话错误: sessionId={}, error={}", sessionId, throwable.getMessage());
        });
        return RegistrationStatus.REGISTERED;
    }

    /** 移除对话会话
     * @param sessionId 会话ID */
    public void removeSession(String sessionId) {
        SessionInfo removed = activeSessions.remove(sessionId);
        if (removed != null) {
            long duration = System.currentTimeMillis() - removed.getStartTime();
            logger.info("移除对话会话: sessionId={}, 持续时间={}ms", sessionId, duration);
        }
    }

    private void removeSession(String sessionId, SessionInfo expectedSession, String reason) {
        boolean removed = activeSessions.remove(sessionId, expectedSession);
        if (removed) {
            long duration = System.currentTimeMillis() - expectedSession.getStartTime();
            logger.info("移除对话会话: sessionId={}, reason={}, 持续时间={}ms", sessionId, reason, duration);
        }
    }

    @Scheduled(fixedDelayString = "${chat.session.cleanup-fixed-delay-ms:60000}",
            initialDelayString = "${chat.session.cleanup-initial-delay-ms:60000}")
    public void cleanupExpiredSessions() {
        long maxActiveSessionMs = chatSessionProperties.getMaxActiveMs();
        if (maxActiveSessionMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!activeSessions.isEmpty()) {
            activeSessions.forEach((sessionId, sessionInfo) -> {
                long age = now - sessionInfo.getStartTime();
                if (age <= maxActiveSessionMs) {
                    return;
                }
                boolean removed = activeSessions.remove(sessionId, sessionInfo);
                if (removed) {
                logger.warn("清理超时活跃对话会话: sessionId={}, turnId={}, age={}ms, max={}ms", sessionId,
                        sessionInfo.getTurnId(), age, maxActiveSessionMs);
                    SseEmitterUtils.safeComplete(sessionInfo.getEmitter());
                }
            });
        }
        interruptedSessions.forEach((sessionId, interruptedAt) -> {
            if (now - interruptedAt > Math.max(60000L, maxActiveSessionMs)) {
                interruptedSessions.remove(sessionId, interruptedAt);
            }
        });
    }

    /** 中断指定的对话会话
     * @param sessionId 会话ID
     * @return 是否成功中断（true表示会话存在且成功中断，false表示会话不存在） */
    public boolean interruptSession(String sessionId) {
        SessionInfo sessionInfo = activeSessions.get(sessionId);
        if (sessionInfo == null) {
            logger.warn("尝试中断不存在的会话: sessionId={}", sessionId);
            return false;
        }
        return interruptSession(sessionInfo, "对话已被中断");
    }

    private boolean interruptSession(SessionInfo sessionInfo, String message) {
        if (sessionInfo == null) {
            return false;
        }
        sessionInfo.setInterrupted();
        interruptedSessions.put(buildInterruptedKey(sessionInfo.getSessionId(), sessionInfo.getTurnId()),
                System.currentTimeMillis());
        logger.info("设置会话中断标志: sessionId={}, turnId={}", sessionInfo.getSessionId(), sessionInfo.getTurnId());

        try {
            SseEmitter emitter = sessionInfo.getEmitter();

            SseEmitterUtils.safeSend(emitter,
                    SseEmitter.event().name("interrupt")
                            .data("{\"interrupted\": true, \"message\": \"" + escapeJson(message) + "\"}"));
            SseEmitterUtils.safeComplete(emitter);
            logger.info("对话会话已中断: sessionId={}", sessionInfo.getSessionId());
            return true;

        } catch (Exception e) {
            logger.error("中断会话时发生错误: sessionId={}, error={}", sessionInfo.getSessionId(), e.getMessage());
            return true;
        }
    }

    /** 检查会话是否已被中断
     * @param sessionId 会话ID
     * @return true表示已中断，false表示未中断或会话不存在 */
    public boolean isSessionInterrupted(String sessionId, String turnId) {
        SessionInfo sessionInfo = activeSessions.get(sessionId);
        if (sessionInfo != null && sessionInfo.isInterrupted() && sameTurn(sessionInfo.getTurnId(), turnId)) {
            return true;
        }
        String interruptedKey = buildInterruptedKey(sessionId, turnId);
        Long interruptedAt = interruptedSessions.get(interruptedKey);
        if (interruptedAt == null) {
            return false;
        }
        long ttlMs = Math.max(60000L, chatSessionProperties.getMaxActiveMs());
        if (System.currentTimeMillis() - interruptedAt <= ttlMs) {
            return true;
        }
        interruptedSessions.remove(interruptedKey, interruptedAt);
        return false;
    }

    public boolean isSessionInterrupted(String sessionId) {
        SessionInfo sessionInfo = activeSessions.get(sessionId);
        return sessionInfo != null && sessionInfo.isInterrupted();
    }

    /** 获取当前活跃会话数量
     * @return 活跃会话数量 */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /** 检查会话是否存在
     * @param sessionId 会话ID
     * @return 会话是否存在 */
    public boolean hasSession(String sessionId) {
        return activeSessions.containsKey(sessionId);
    }

    private String buildInterruptedKey(String sessionId, String turnId) {
        return sessionId + ":" + (turnId == null ? "" : turnId);
    }

    private boolean sameTurn(String currentTurnId, String expectedTurnId) {
        if (expectedTurnId == null || expectedTurnId.isBlank()) {
            return true;
        }
        return expectedTurnId.equals(currentTurnId);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
