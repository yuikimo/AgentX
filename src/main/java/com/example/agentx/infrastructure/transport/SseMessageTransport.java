package com.example.agentx.infrastructure.transport;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.config.ChatSseProperties;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.domain.conversation.constant.MessageType;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** SSE消息传输实现 */
@Component
public class SseMessageTransport implements MessageTransport<SseEmitter> {

    private static final Logger logger = LoggerFactory.getLogger(SseMessageTransport.class);
    private static final long FLUSH_INTERVAL_MS = 30L;
    private static final int MAX_BATCHED_FRAGMENTS = 16;
    private static final long HEARTBEAT_INTERVAL_MS = 15000L;
    private static final EnumSet<MessageType> BATCHABLE_TYPES = EnumSet.of(
            MessageType.TEXT,
            MessageType.RAG_ANSWER_PROGRESS,
            MessageType.RAG_THINKING_PROGRESS);

    private final ScheduledExecutorService scheduler;
    private final Map<SseEmitter, EmitterState> emitterStates = new ConcurrentHashMap<>();

    public SseMessageTransport(ChatSseProperties chatSseProperties) {
        this.scheduler = Executors.newScheduledThreadPool(Math.max(2, chatSseProperties.getSchedulerPoolSize()),
                new TransportThreadFactory());
    }

    @Override
    public SseEmitter createConnection(long timeout) {
        SseEmitter emitter = new SseEmitter(timeout);
        EmitterState state = new EmitterState();
        emitterStates.put(emitter, state);
        startHeartbeat(emitter, state);
        emitter.onCompletion(() -> cleanupEmitterState(emitter));
        emitter.onTimeout(() -> cleanupEmitterState(emitter));
        emitter.onError(error -> cleanupEmitterState(emitter));
        return emitter;
    }

    @Override
    public void sendMessage(SseEmitter connection, AgentChatResponse streamChatResponse) {
        if (connection == null || streamChatResponse == null) {
            return;
        }
        if (shouldBatch(streamChatResponse)) {
            bufferMessage(connection, streamChatResponse);
            return;
        }
        flushPending(connection);
        safeSendDirect(connection, streamChatResponse);
    }

    @Override
    public void sendEndMessage(SseEmitter connection, AgentChatResponse streamChatResponse) {
        try {
            flushPending(connection);
            safeSendDirect(connection, streamChatResponse);
        } finally {
            safeCompleteEmitter(connection);
        }
    }

    @Override
    public void completeConnection(SseEmitter connection) {
        flushPending(connection);
        safeCompleteEmitter(connection);
    }

    @Override
    public void handleError(SseEmitter connection, Throwable error) {
        try {
            flushPending(connection);
            safeSendDirect(connection, ChatErrorResponseFactory.fromThrowable(error));
        } finally {
            safeCompleteEmitter(connection);
        }
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
        emitterStates.clear();
    }

    private boolean shouldBatch(AgentChatResponse response) {
        return response != null
                && !response.isDone()
                && response.getContent() != null
                && !response.getContent().isEmpty()
                && response.getPayload() == null
                && response.getTaskId() == null
                && (response.getTasks() == null || response.getTasks().isEmpty())
                && response.getErrorCode() == null
                && response.getUserMessage() == null
                && BATCHABLE_TYPES.contains(response.getMessageType());
    }

    private void bufferMessage(SseEmitter emitter, AgentChatResponse response) {
        EmitterState state = emitterStates.computeIfAbsent(emitter, key -> new EmitterState());
        synchronized (state.lock) {
            if (!canAppend(state.bufferedMessage, response)) {
                flushBufferedLocked(emitter, state);
            }
            if (state.bufferedMessage == null) {
                state.bufferedMessage = copyResponse(response);
                state.bufferedMessage.setContent("");
            }
            state.bufferedContent.append(response.getContent());
            state.bufferedFragments++;
            state.lastActivityAt = System.currentTimeMillis();
            if (state.bufferedFragments >= MAX_BATCHED_FRAGMENTS) {
                cancelFlushLocked(state);
                flushBufferedLocked(emitter, state);
                return;
            }
            scheduleFlushLocked(emitter, state);
        }
    }

    private boolean canAppend(AgentChatResponse buffered, AgentChatResponse incoming) {
        if (buffered == null || incoming == null) {
            return false;
        }
        if (buffered.getMessageType() != incoming.getMessageType()) {
            return false;
        }
        if (!safeEquals(buffered.getPayload(), incoming.getPayload())) {
            return false;
        }
        return safeEquals(buffered.getTaskId(), incoming.getTaskId());
    }

    private void scheduleFlushLocked(SseEmitter emitter, EmitterState state) {
        if (state.flushFuture != null && !state.flushFuture.isDone()) {
            return;
        }
        state.flushFuture = scheduler.schedule(() -> flushPending(emitter), FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void flushPending(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        EmitterState state = emitterStates.get(emitter);
        if (state == null) {
            return;
        }
        synchronized (state.lock) {
            cancelFlushLocked(state);
            flushBufferedLocked(emitter, state);
        }
    }

    private void flushBufferedLocked(SseEmitter emitter, EmitterState state) {
        if (state.bufferedMessage == null || state.bufferedContent.length() == 0) {
            state.bufferedMessage = null;
            state.bufferedContent.setLength(0);
            state.bufferedFragments = 0;
            return;
        }
        AgentChatResponse merged = copyResponse(state.bufferedMessage);
        merged.setContent(state.bufferedContent.toString());
        state.bufferedMessage = null;
        state.bufferedContent.setLength(0);
        state.bufferedFragments = 0;
        safeSendDirect(emitter, merged);
    }

    private void cancelFlushLocked(EmitterState state) {
        if (state.flushFuture == null) {
            return;
        }
        state.flushFuture.cancel(false);
        state.flushFuture = null;
    }

    private void startHeartbeat(SseEmitter emitter, EmitterState state) {
        state.heartbeatFuture = scheduler.scheduleAtFixedRate(() -> sendHeartbeatIfIdle(emitter),
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeatIfIdle(SseEmitter emitter) {
        EmitterState state = emitterStates.get(emitter);
        if (state == null) {
            return;
        }
        long idleMs = System.currentTimeMillis() - state.lastActivityAt;
        if (idleMs < HEARTBEAT_INTERVAL_MS) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
            state.lastActivityAt = System.currentTimeMillis();
        } catch (IllegalStateException e) {
            logger.debug("SSE连接已关闭，跳过心跳发送: {}", e.getMessage());
            cleanupEmitterState(emitter);
        } catch (IOException e) {
            logger.warn("SSE心跳发送失败: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("SSE心跳发送异常: {}", e.getMessage(), e);
        }
    }

    /** 安全发送消息，直接处理网络异常
     * @param emitter SSE发送器
     * @param response 响应消息 */
    private void safeSendDirect(SseEmitter emitter, AgentChatResponse response) {
        if (emitter == null || response == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(response));
            EmitterState state = emitterStates.get(emitter);
            if (state != null) {
                state.lastActivityAt = System.currentTimeMillis();
            }
        } catch (IllegalStateException e) {
            logger.debug("SSE连接已关闭，跳过消息发送: {}", e.getMessage());
            cleanupEmitterState(emitter);
        } catch (IOException e) {
            logger.warn("SSE网络异常，跳过消息发送: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("SSE消息发送异常: {}", e.getMessage(), e);
        }
    }

    /** 安全完成SSE连接
     * @param emitter SSE发送器 */
    private void safeCompleteEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            logger.debug("SSE连接已完成或已关闭: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("完成SSE连接时异常: {}", e.getMessage(), e);
        } finally {
            cleanupEmitterState(emitter);
        }
    }

    private void cleanupEmitterState(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        EmitterState removed = emitterStates.remove(emitter);
        if (removed == null) {
            return;
        }
        synchronized (removed.lock) {
            cancelFlushLocked(removed);
            if (removed.heartbeatFuture != null) {
                removed.heartbeatFuture.cancel(false);
                removed.heartbeatFuture = null;
            }
            removed.bufferedMessage = null;
            removed.bufferedContent.setLength(0);
            removed.bufferedFragments = 0;
        }
    }

    private AgentChatResponse copyResponse(AgentChatResponse source) {
        AgentChatResponse copy = new AgentChatResponse();
        copy.setContent(source.getContent());
        copy.setDone(source.isDone());
        copy.setMessageType(source.getMessageType());
        copy.setTaskId(source.getTaskId());
        copy.setPayload(source.getPayload());
        copy.setTimestamp(source.getTimestamp());
        copy.setErrorCode(source.getErrorCode());
        copy.setUserMessage(source.getUserMessage());
        copy.setTasks(source.getTasks() == null ? null : Collections.unmodifiableList(source.getTasks()));
        return copy;
    }

    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static final class EmitterState {
        private final Object lock = new Object();
        private final StringBuilder bufferedContent = new StringBuilder();
        private volatile long lastActivityAt = System.currentTimeMillis();
        private AgentChatResponse bufferedMessage;
        private int bufferedFragments;
        private ScheduledFuture<?> flushFuture;
        private ScheduledFuture<?> heartbeatFuture;
    }

    private static final class TransportThreadFactory implements ThreadFactory {
        private int threadNo = 0;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agentx-sse-transport-" + (++threadNo));
            thread.setDaemon(true);
            return thread;
        }
    }
}
