package com.example.agentx.application.conversation.service.message.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceTemplate;
import dev.langchain4j.mcp.client.transport.PresetParameter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 可复用 MCP 客户端租约。close() 表示释放租约，而不是直接关闭底层连接。 */
class PooledMcpClient implements McpClient {

    private final PooledClientHolder holder;
    private final long toolSpecCacheTtlMs;
    private final Runnable releaseCallback;
    private final AtomicBoolean released = new AtomicBoolean(false);

    PooledMcpClient(PooledClientHolder holder, long toolSpecCacheTtlMs, Runnable releaseCallback) {
        this.holder = holder;
        this.toolSpecCacheTtlMs = toolSpecCacheTtlMs;
        this.releaseCallback = releaseCallback;
        this.holder.retain();
    }

    @Override
    public String key() {
        return holder.callWithReadLock(() -> holder.client().key());
    }

    @Override
    public List<ToolSpecification> listTools() {
        return holder.listTools(toolSpecCacheTtlMs);
    }

    @Override
    public String executeTool(ToolExecutionRequest request) {
        return holder.callWithReadLock(() -> holder.client().executeTool(request));
    }

    @Override
    public List<McpResource> listResources() {
        return holder.callWithReadLock(() -> holder.client().listResources());
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates() {
        return holder.callWithReadLock(() -> holder.client().listResourceTemplates());
    }

    @Override
    public McpReadResourceResult readResource(String uri) {
        return holder.callWithReadLock(() -> holder.client().readResource(uri));
    }

    @Override
    public List<McpPrompt> listPrompts() {
        return holder.callWithReadLock(() -> holder.client().listPrompts());
    }

    @Override
    public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) {
        return holder.callWithReadLock(() -> holder.client().getPrompt(name, arguments));
    }

    @Override
    public void checkHealth() {
        holder.runWithReadLock(() -> holder.client().checkHealth());
    }

    @Override
    public void presetParameters(List<PresetParameter> presetParameters) {
        holder.runWithWriteLock(() -> holder.client().presetParameters(presetParameters));
    }

    void invalidateToolSpecCache() {
        holder.invalidateToolSpecCache();
    }

    @Override
    public void close() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        holder.release();
        releaseCallback.run();
    }

    static class PooledClientHolder {
        private final McpClient client;
        private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
        private final AtomicInteger activeLeases = new AtomicInteger(0);
        private volatile long lastReleasedAt = System.currentTimeMillis();
        private volatile long lastAccessedAt = System.currentTimeMillis();
        private volatile CachedToolSpecifications cachedToolSpecifications;

        PooledClientHolder(McpClient client) {
            this.client = client;
        }

        McpClient client() {
            return client;
        }

        List<ToolSpecification> listTools(long ttlMs) {
            long now = System.currentTimeMillis();
            if (ttlMs > 0) {
                CachedToolSpecifications cached = cachedToolSpecifications;
                if (cached != null && now - cached.cachedAt() <= ttlMs) {
                    touch();
                    return cached.tools();
                }
            }
            lifecycleLock.writeLock().lock();
            try {
                now = System.currentTimeMillis();
                if (ttlMs > 0) {
                    CachedToolSpecifications cached = cachedToolSpecifications;
                    if (cached != null && now - cached.cachedAt() <= ttlMs) {
                        touch();
                        return cached.tools();
                    }
                }
                List<ToolSpecification> tools = client.listTools();
                if (ttlMs > 0) {
                    cachedToolSpecifications = new CachedToolSpecifications(tools, now);
                }
                touch();
                return tools;
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        }

        <T> T callWithReadLock(java.util.concurrent.Callable<T> callable) {
            lifecycleLock.readLock().lock();
            try {
                touch();
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        void runWithReadLock(Runnable runnable) {
            lifecycleLock.readLock().lock();
            try {
                touch();
                runnable.run();
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        void runWithWriteLock(Runnable runnable) {
            lifecycleLock.writeLock().lock();
            try {
                touch();
                runnable.run();
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        }

        void invalidateToolSpecCache() {
            lifecycleLock.writeLock().lock();
            try {
                cachedToolSpecifications = null;
                touch();
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        }

        void retain() {
            activeLeases.incrementAndGet();
            touch();
        }

        void release() {
            activeLeases.updateAndGet(value -> Math.max(0, value - 1));
            lastReleasedAt = System.currentTimeMillis();
            touch();
        }

        boolean canEvict(long ttlMs) {
            return activeLeases.get() == 0 && System.currentTimeMillis() - lastReleasedAt >= ttlMs;
        }

        boolean isIdle() {
            return activeLeases.get() == 0;
        }

        long lastAccessedAt() {
            return lastAccessedAt;
        }

        void closeUnderlying() throws Exception {
            lifecycleLock.writeLock().lock();
            try {
                client.close();
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        }

        private void touch() {
            lastAccessedAt = System.currentTimeMillis();
        }

        private record CachedToolSpecifications(List<ToolSpecification> tools, long cachedAt) {
        }
    }
}
