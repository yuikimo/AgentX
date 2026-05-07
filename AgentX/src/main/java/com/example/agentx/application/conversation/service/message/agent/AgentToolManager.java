package com.example.agentx.application.conversation.service.message.agent;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.McpUrlProviderService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Agent工具管理器 负责创建和管理工具提供者 */
@Component
public class AgentToolManager {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolManager.class);
    private static final String INIT_TIMEOUT_MESSAGE = "工具服务初始化超时（后台继续准备）";
    private static final int WARMUP_FAILURE_CACHE_MAX_SIZE = 10000;
    private static final long MIN_WARMUP_FAILURE_TTL_MS = 5000L;

    private final McpUrlProviderService mcpUrlProviderService;
    private final McpClientPoolManager mcpClientPoolManager;
    private final TaskExecutor mcpClientInitTaskExecutor;
    private final TaskExecutor mcpToolExecutionTaskExecutor;
    private final ChatToolProperties chatToolProperties;
    private final ConcurrentMap<String, CompletableFuture<McpWarmupResult>> mcpWarmupTasks = new ConcurrentHashMap<>();
    private final Cache<String, String> mcpWarmupFailures;

    public AgentToolManager(McpUrlProviderService mcpUrlProviderService, McpClientPoolManager mcpClientPoolManager,
            @Qualifier("mcpClientInitTaskExecutor") TaskExecutor mcpClientInitTaskExecutor,
            @Qualifier("mcpToolExecutionTaskExecutor") TaskExecutor mcpToolExecutionTaskExecutor,
            ChatToolProperties chatToolProperties) {
        this.mcpUrlProviderService = mcpUrlProviderService;
        this.mcpClientPoolManager = mcpClientPoolManager;
        this.mcpClientInitTaskExecutor = mcpClientInitTaskExecutor;
        this.mcpToolExecutionTaskExecutor = mcpToolExecutionTaskExecutor;
        this.chatToolProperties = chatToolProperties;
        this.mcpWarmupFailures = CacheBuilder.newBuilder().maximumSize(WARMUP_FAILURE_CACHE_MAX_SIZE)
                .expireAfterWrite(Math.max(MIN_WARMUP_FAILURE_TTL_MS,
                        chatToolProperties.getMcp().getNegativeCacheTtlMs()), TimeUnit.MILLISECONDS)
                .build();
    }

    /** 创建工具提供者（支持全局/用户隔离工具自动识别）
     *
     * @param mcpServerNames 工具服务名列表
     * @param toolPresetParams 工具预设参数
     * @param userId 用户ID（关键参数：用于用户隔离工具）
     * @return 工具提供者实例，如果工具列表为空则返回null */
    public ToolProvider createToolProvider(List<String> mcpServerNames,
            Map<String, Map<String, Map<String, String>>> toolPresetParams, String userId) {
        return buildToolProvider(mcpServerNames, toolPresetParams, userId).toolProvider();
    }

    /** 提前启动 MCP warmup，将冷启动开销前移到会话打开阶段。 */
    public void prewarmToolServers(List<String> mcpServerNames, String userId) {
        if (mcpServerNames == null || mcpServerNames.isEmpty()) {
            return;
        }
        for (String serverName : new LinkedHashSet<>(mcpServerNames)) {
            if (serverName == null || serverName.isBlank()) {
                continue;
            }
            getOrStartWarmupTask(serverName, userId);
        }
    }

    public ToolProviderBuildResult buildToolProvider(List<String> mcpServerNames,
            Map<String, Map<String, Map<String, String>>> toolPresetParams, String userId) {
        if (mcpServerNames == null || mcpServerNames.isEmpty()) {
            return new ToolProviderBuildResult(null, List.of(), List.of());
        }

        List<String> uniqueServerNames = new ArrayList<>(new LinkedHashSet<>(mcpServerNames));
        Map<String, CompletableFuture<McpWarmupResult>> warmupFutures = uniqueServerNames.stream().collect(
                Collectors.toMap(serverName -> serverName, serverName -> getOrStartWarmupTask(serverName, userId)));

        List<McpClient> mcpClients = new ArrayList<>(warmupFutures.size());
        List<String> availableServerNames = new ArrayList<>(warmupFutures.size());
        List<String> unavailableMessages = new ArrayList<>();
        long timeoutMs = resolveMcpInitGraceMs();
        try {
            if (timeoutMs > 0L) {
                CompletableFuture.allOf(warmupFutures.values().toArray(new CompletableFuture[0])).get(timeoutMs,
                        TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            logger.warn("MCP 客户端初始化超过首轮等待窗口，将跳过未完成工具并继续后台初始化: graceMs={}", timeoutMs);
        } catch (Exception e) {
            logger.warn("MCP 客户端初始化存在失败，将继续使用已成功初始化的工具: {}", e.getMessage());
        }

        for (String serverName : uniqueServerNames) {
            CompletableFuture<McpWarmupResult> future = warmupFutures.get(serverName);
            if (future == null) {
                unavailableMessages.add("工具服务「" + serverName + "」初始化任务丢失");
                continue;
            }
            if (!future.isDone()) {
                unavailableMessages.add(INIT_TIMEOUT_MESSAGE);
                continue;
            }
            try {
                McpWarmupResult warmupResult = future.getNow(null);
                if (warmupResult != null && warmupResult.sseUrl() != null) {
                    mcpClients.add(createMcpClient(warmupResult.serverName(), warmupResult.sseUrl(), toolPresetParams,
                            userId));
                    availableServerNames.add(warmupResult.serverName());
                } else if (warmupResult != null && warmupResult.errorMessage() != null) {
                    logger.warn("跳过不可用 MCP 工具: server={}, error={}", warmupResult.serverName(),
                            warmupResult.errorMessage());
                    unavailableMessages.add("工具服务「" + warmupResult.serverName() + "」不可用");
                }
            } catch (Exception e) {
                logger.warn("跳过不可用 MCP 工具: {}", e.getMessage());
                unavailableMessages.add("工具服务「" + serverName + "」不可用");
            }
        }

        List<String> normalizedUnavailableMessages = normalizeUnavailableMessages(unavailableMessages);

        if (mcpClients.isEmpty()) {
            logger.warn("未能初始化任何 MCP 工具，本轮将以无外部工具模式继续");
            return new ToolProviderBuildResult(null, availableServerNames, normalizedUnavailableMessages);
        }

        ToolProvider delegate = McpToolProvider.builder().mcpClients(mcpClients).build();
        ChatToolProperties.Mcp mcpProps = chatToolProperties.getMcp();
        ManagedMcpToolProvider managedToolProvider = new ManagedMcpToolProvider(delegate, mcpClients,
                chatToolProperties.getMaxCalls(), chatToolProperties.getMaxCallsPerTool(),
                Duration.ofMillis(mcpProps.getExecutionTimeoutMs()), chatToolProperties.getResultCacheTtlMs(),
                mcpProps.getMaxResultChars(), mcpToolExecutionTaskExecutor, mcpProps.getCacheableToolNamePatterns(),
                mcpProps.getNonCacheableToolNamePatterns());
        managedToolProvider.prefetchToolDefinitions();
        return new ToolProviderBuildResult(managedToolProvider, availableServerNames, normalizedUnavailableMessages);
    }

    @PreDestroy
    public void shutdown() {
        mcpWarmupTasks.clear();
        mcpWarmupFailures.invalidateAll();
    }

    /** 获取可用的工具列表
     *
     * @return 工具URL列表 */
    public List<String> getAvailableTools(ChatContext chatContext) {
        return chatContext.getMcpServerNames();
    }

    private McpClient createMcpClient(String mcpServerName, String sseUrl,
            Map<String, Map<String, Map<String, String>>> toolPresetParams, String userId) {
        return mcpClientPoolManager.borrowClient(mcpServerName, sseUrl, toolPresetParams, userId);
    }

    private long resolveMcpInitGraceMs() {
        long configuredGraceMs = Math.max(0L, chatToolProperties.getMcp().getInitGraceMs());
        long configuredTimeoutMs = Math.max(0L, chatToolProperties.getMcp().getInitTimeoutMs());
        if (configuredTimeoutMs == 0L) {
            return configuredGraceMs;
        }
        return Math.min(configuredGraceMs, configuredTimeoutMs);
    }

    private McpWarmupResult warmupMcpServer(String mcpServerName, String userId) {
        try {
            String sseUrl = mcpUrlProviderService.getMcpToolUrl(mcpServerName, userId);
            return new McpWarmupResult(mcpServerName, sseUrl, null, false);
        } catch (Exception e) {
            return new McpWarmupResult(mcpServerName, null, e.getMessage(), isTransientWarmupFailure(e));
        }
    }

    private CompletableFuture<McpWarmupResult> getOrStartWarmupTask(String mcpServerName, String userId) {
        String key = buildWarmupKey(mcpServerName, userId);
        String cachedFailure = mcpWarmupFailures.getIfPresent(key);
        if (cachedFailure != null) {
            return CompletableFuture.completedFuture(new McpWarmupResult(mcpServerName, null, cachedFailure, false));
        }

        for (;;) {
            CompletableFuture<McpWarmupResult> existing = mcpWarmupTasks.get(key);
            if (existing != null) {
                return existing;
            }

            CompletableFuture<McpWarmupResult> created = CompletableFuture
                    .supplyAsync(() -> warmupMcpServer(mcpServerName, userId), mcpClientInitTaskExecutor);
            CompletableFuture<McpWarmupResult> previous = mcpWarmupTasks.putIfAbsent(key, created);
            if (previous == null) {
                created.whenComplete((result, throwable) -> {
                    mcpWarmupTasks.remove(key, created);
                    if (throwable != null) {
                        cacheWarmupFailureIfStable(key, throwable);
                    } else if (result != null && result.errorMessage() != null && !result.transientFailure()) {
                        cacheWarmupFailure(key, result.errorMessage());
                    } else {
                        mcpWarmupFailures.invalidate(key);
                    }
                });
                return created;
            }

            created.cancel(false);
        }
    }

    private String buildWarmupKey(String mcpServerName, String userId) {
        return mcpServerName + "::" + (userId == null ? "" : userId);
    }

    private void cacheWarmupFailure(String key, String errorMessage) {
        mcpWarmupFailures.put(key, errorMessage);
    }

    private void cacheWarmupFailureIfStable(String key, Throwable throwable) {
        if (isTransientWarmupFailure(throwable)) {
            mcpWarmupFailures.invalidate(key);
            return;
        }
        cacheWarmupFailure(key, throwable != null ? throwable.getMessage() : "unknown error");
    }

    private boolean isTransientWarmupFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return isTransientWarmupFailureMessage(throwable != null ? throwable.getMessage() : null);
    }

    private boolean isTransientWarmupFailureMessage(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("timeout") || normalized.contains("timed out")
                || message.contains("超时") || message.contains("后台继续准备");
    }

    private record McpWarmupResult(String serverName, String sseUrl, String errorMessage, boolean transientFailure) {
    }

    public String buildToolAvailabilityNotice(List<String> unavailableMessages) {
        if (unavailableMessages == null || unavailableMessages.isEmpty()) {
            return "";
        }
        return "外部工具部分不可用（仅用于内部决策，不要向用户复述）："
                + String.join("；", normalizeUnavailableMessages(unavailableMessages))
                + "。请基于现有上下文与仍可用的工具继续回答。";
    }

    private List<String> normalizeUnavailableMessages(List<String> unavailableMessages) {
        if (unavailableMessages == null || unavailableMessages.isEmpty()) {
            return List.of();
        }
        int timeoutCount = 0;
        List<String> normalized = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (String message : unavailableMessages) {
            if (message == null) {
                continue;
            }
            String trimmedMessage = message.trim();
            if (trimmedMessage.isEmpty()) {
                continue;
            }
            if ("工具服务初始化超时".equals(trimmedMessage) || INIT_TIMEOUT_MESSAGE.equals(trimmedMessage)) {
                timeoutCount++;
                continue;
            }
            if (seen.putIfAbsent(trimmedMessage, Boolean.TRUE) == null) {
                normalized.add(trimmedMessage);
            }
        }
        if (timeoutCount == 1) {
            normalized.add(0, "1个工具服务初始化超时");
        } else if (timeoutCount > 1) {
            normalized.add(0, timeoutCount + "个工具服务初始化超时");
        }
        return normalized;
    }

    public record ToolProviderBuildResult(ToolProvider toolProvider, List<String> availableServerNames,
            List<String> unavailableMessages) {
    }
}
