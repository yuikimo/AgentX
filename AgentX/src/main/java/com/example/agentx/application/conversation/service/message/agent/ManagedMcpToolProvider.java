package com.example.agentx.application.conversation.service.message.agent;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import com.example.agentx.application.conversation.service.message.ToolPayloadUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** MCP 工具提供者包装器：统一处理限次、超时、重名过滤与资源释放。 */
public class ManagedMcpToolProvider implements ToolProvider, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ManagedMcpToolProvider.class);
    private static final int REQUEST_FINGERPRINT_COUNTER_MAX_SIZE = 2048;
    private static final int TOOL_RESULT_CACHE_MAX_SIZE = 512;
    private static final int FAILED_FINGERPRINT_CACHE_MAX_SIZE = 2048;
    private static final int EXCLUDED_TOOL_NAME_CACHE_MAX_SIZE = 512;
    private static final Duration REQUEST_FINGERPRINT_COUNTER_TTL = Duration.ofMinutes(10);
    private static final Duration PARAM_VALIDATION_FAILURE_TTL = Duration.ofMinutes(5);
    private static final Duration TRANSIENT_FAILURE_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_FAILURE_TTL = Duration.ofMinutes(2);

    private final ToolProvider delegate;
    private final List<McpClient> mcpClients;
    private final TaskExecutor toolExecutionTaskExecutor;
    private final int maxToolCalls;
    private final int maxToolCallsPerTool;
    private final Duration executionTimeout;
    private final long resultCacheTtlMs;
    private final int maxResultChars;
    private final List<String> cacheableToolNamePatterns;
    private final List<String> nonCacheableToolNamePatterns;
    private AtomicInteger toolCallCount = new AtomicInteger(0);
    private final Cache<String, AtomicInteger> requestFingerprintCallCount;
    private final Cache<String, String> cachedToolResults;
    private final Cache<String, FailedToolFingerprint> failedToolFingerprints;
    private final Cache<String, Boolean> excludedToolNames;
    private final Object consecutiveRequestLock = new Object();
    private final AtomicReference<CompletableFuture<ToolProviderResult>> toolProviderLoadFuture = new AtomicReference<>();
    private String lastRequestFingerprint;
    private int consecutiveSameRequestCount;
    private volatile ToolProviderResult cachedToolProviderResult;

    public ManagedMcpToolProvider(ToolProvider delegate, List<McpClient> mcpClients, int maxToolCalls,
            int maxToolCallsPerTool, Duration executionTimeout, long resultCacheTtlMs, int maxResultChars,
            TaskExecutor toolExecutionTaskExecutor, List<String> cacheableToolNamePatterns,
            List<String> nonCacheableToolNamePatterns) {
        this.delegate = delegate;
        this.mcpClients = mcpClients;
        this.toolExecutionTaskExecutor = toolExecutionTaskExecutor;
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.maxToolCallsPerTool = Math.max(1, maxToolCallsPerTool);
        this.executionTimeout = executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()
                ? Duration.ofSeconds(60)
                : executionTimeout;
        this.resultCacheTtlMs = Math.max(1000L, resultCacheTtlMs);
        this.maxResultChars = Math.max(500, maxResultChars);
        this.cacheableToolNamePatterns = normalizePatterns(cacheableToolNamePatterns);
        this.nonCacheableToolNamePatterns = normalizePatterns(nonCacheableToolNamePatterns);
        this.requestFingerprintCallCount = CacheBuilder.newBuilder()
                .maximumSize(REQUEST_FINGERPRINT_COUNTER_MAX_SIZE)
                .expireAfterWrite(REQUEST_FINGERPRINT_COUNTER_TTL)
                .build();
        this.cachedToolResults = CacheBuilder.newBuilder()
                .maximumSize(TOOL_RESULT_CACHE_MAX_SIZE)
                .expireAfterWrite(this.resultCacheTtlMs, TimeUnit.MILLISECONDS)
                .build();
        this.failedToolFingerprints = CacheBuilder.newBuilder()
                .maximumSize(FAILED_FINGERPRINT_CACHE_MAX_SIZE)
                .build();
        this.excludedToolNames = CacheBuilder.newBuilder()
                .maximumSize(EXCLUDED_TOOL_NAME_CACHE_MAX_SIZE)
                .build();
    }

    public void excludeToolNames(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String toolName : toolNames) {
            if (toolName != null && excludedToolNames.getIfPresent(toolName) == null) {
                excludedToolNames.put(toolName, Boolean.TRUE);
                changed = true;
            }
        }
        if (changed) {
            toolProviderLoadFuture.set(null);
            cachedToolProviderResult = null;
        }
    }

    public void setSharedToolCallCounter(AtomicInteger toolCallCount) {
        if (toolCallCount != null && toolCallCount != this.toolCallCount) {
            toolCallCount.accumulateAndGet(this.toolCallCount.get(), Math::max);
            this.toolCallCount = toolCallCount;
        }
    }

    public AtomicInteger getSharedToolCallCounter() {
        return toolCallCount;
    }

    public Optional<ToolProviderResult> getCachedToolProviderResult() {
        return Optional.ofNullable(cachedToolProviderResult);
    }

    public void prefetchToolDefinitions() {
        if (cachedToolProviderResult != null) {
            return;
        }
        try {
            toolExecutionTaskExecutor.execute(() -> {
                try {
                    provideTools(new ToolProviderRequest("catalog-prefetch", UserMessage.from("")));
                } catch (Exception e) {
                    logger.debug("后台预拉 MCP 工具定义失败: {}", e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            logger.debug("提交 MCP 工具定义预拉任务失败: {}", e.getMessage());
        }
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult cached = cachedToolProviderResult;
        if (cached != null) {
            return cached;
        }

        for (;;) {
            CompletableFuture<ToolProviderResult> future = toolProviderLoadFuture.get();
            if (future != null) {
                return awaitToolProviderResult(future);
            }

            CompletableFuture<ToolProviderResult> created = new CompletableFuture<>();
            if (!toolProviderLoadFuture.compareAndSet(null, created)) {
                continue;
            }

            try {
                ToolProviderResult loaded = loadToolProviderResult(request);
                cachedToolProviderResult = loaded;
                created.complete(loaded);
                return loaded;
            } catch (Throwable throwable) {
                toolProviderLoadFuture.compareAndSet(created, null);
                created.completeExceptionally(throwable);
                throw rethrowToolProviderLoadFailure(throwable);
            }
        }
    }

    private ToolProviderResult loadToolProviderResult(ToolProviderRequest request) {
        ToolProviderResult delegateResult = delegate.provideTools(request);
        Map<ToolSpecification, ToolExecutor> wrappedTools = new LinkedHashMap<>();
        if (delegateResult == null || delegateResult.tools() == null || delegateResult.tools().isEmpty()) {
            return new ToolProviderResult(wrappedTools);
        }

        for (Map.Entry<ToolSpecification, ToolExecutor> entry : delegateResult.tools().entrySet()) {
            ToolSpecification toolSpecification = entry.getKey();
            if (toolSpecification == null) {
                continue;
            }
            String toolName = toolSpecification.name();
            if (excludedToolNames.getIfPresent(toolName) != null) {
                logger.warn("跳过与内置工具重名的 MCP 工具: {}", toolName);
                continue;
            }
            wrappedTools.put(toolSpecification, wrapExecutor(toolName, entry.getValue()));
        }

        return new ToolProviderResult(wrappedTools);
    }

    private ToolProviderResult awaitToolProviderResult(CompletableFuture<ToolProviderResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 MCP 工具定义加载被中断", e);
        } catch (ExecutionException e) {
            throw rethrowToolProviderLoadFailure(e.getCause() != null ? e.getCause() : e);
        }
    }

    private RuntimeException rethrowToolProviderLoadFailure(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("加载 MCP 工具定义失败", throwable);
    }

    @Override
    public void close() {
        if (mcpClients == null || mcpClients.isEmpty()) {
            return;
        }
        for (McpClient mcpClient : mcpClients) {
            if (mcpClient == null) {
                continue;
            }
            try {
                mcpClient.close();
            } catch (Exception e) {
                logger.debug("关闭 MCP 客户端失败: {}", e.getMessage());
            }
        }
    }

    private ToolExecutor wrapExecutor(String toolName, ToolExecutor delegateExecutor) {
        return (request, memoryId) -> {
            String requestFingerprint = buildRequestFingerprint(toolName, request);
            int consecutiveCount = recordConsecutiveRequest(requestFingerprint);
            if (consecutiveCount > maxToolCallsPerTool) {
                logger.warn("MCP 工具连续重复调用超过阈值: tool={}, fingerprint={}, consecutiveCount={}, max={}",
                        toolName, requestFingerprint, consecutiveCount, maxToolCallsPerTool);
                return "❌ 工具「" + toolName + "」相同参数已连续调用多次，请调整参数、换用其他工具或直接给出结论。";
            }

            if (isFailedFingerprintActive(requestFingerprint)) {
                logger.warn("拦截重复失败的 MCP 工具调用: tool={}, fingerprint={}", toolName, requestFingerprint);
                return "❌ 相同参数的工具调用刚刚已经失败，请不要重复重试，改为调整参数或直接给出结论。";
            }

            boolean cacheableTool = isResultCacheable(toolName);
            String cachedResult = cacheableTool ? getCachedResult(requestFingerprint) : null;
            if (cachedResult != null) {
                logger.debug("命中 MCP 工具结果缓存: tool={}, fingerprint={}", toolName, requestFingerprint);
                return cachedResult;
            }

            int currentCount = toolCallCount.incrementAndGet();
            if (currentCount > maxToolCalls) {
                logger.warn("MCP 工具调用超过阈值: tool={}, count={}, max={}", toolName, currentCount, maxToolCalls);
                return "❌ 本轮工具调用次数已达到上限，请停止继续调用工具并直接给出结论。";
            }

            int currentFingerprintCount = requestFingerprintCallCount
                    .asMap().computeIfAbsent(requestFingerprint, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (currentFingerprintCount > maxToolCallsPerTool) {
                logger.warn("MCP 工具相同参数调用超过阈值: tool={}, fingerprint={}, count={}, max={}", toolName,
                        requestFingerprint, currentFingerprintCount, maxToolCallsPerTool);
                return "❌ 工具「" + toolName + "」相同参数本轮调用次数已达到上限，请调整参数、换用其他工具或直接给出结论。";
            }

            String result = executeWithTimeout(toolName, delegateExecutor, request, memoryId);
            if (ToolPayloadUtils.isToolExecutionSuccessful(result)) {
                if (cacheableTool) {
                    cachedToolResults.put(requestFingerprint, result);
                }
            } else {
                ToolFailureCategory failureCategory = classifyFailure(result);
                failedToolFingerprints.put(requestFingerprint,
                        new FailedToolFingerprint(System.currentTimeMillis() + resolveFailureTtlMs(failureCategory)));
                if (shouldInvalidateToolDefinitions(failureCategory, result)) {
                    invalidateToolDefinitions();
                }
            }
            return result;
        };
    }

    private boolean isFailedFingerprintActive(String requestFingerprint) {
        FailedToolFingerprint failed = failedToolFingerprints.getIfPresent(requestFingerprint);
        if (failed == null) {
            return false;
        }
        if (failed.isExpired()) {
            failedToolFingerprints.invalidate(requestFingerprint);
            return false;
        }
        return true;
    }

    private boolean isResultCacheable(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (matchesAny(normalized, nonCacheableToolNamePatterns)) {
            return false;
        }
        return matchesAny(normalized, cacheableToolNamePatterns);
    }

    private List<String> normalizePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        return patterns.stream().filter(pattern -> pattern != null && !pattern.isBlank())
                .map(pattern -> pattern.trim().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
    }

    private boolean matchesAny(String value, List<String> patterns) {
        if (value == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(value::contains);
    }

    private ToolFailureCategory classifyFailure(String result) {
        String normalized = result == null ? "" : result.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("http 400") || normalized.contains("status code: 400")
                || normalized.contains("bad request") || normalized.contains("参数校验")
                || normalized.contains("invalid parameter") || normalized.contains("invalid params")
                || normalized.contains("schema")) {
            return ToolFailureCategory.PARAM_VALIDATION;
        }
        if (normalized.contains("timeout") || normalized.contains("超时") || normalized.contains("timed out")
                || normalized.contains("connect refused") || normalized.contains("connection reset")
                || normalized.contains("connection refused")) {
            return ToolFailureCategory.TRANSIENT;
        }
        if (normalized.contains("unknown tool") || normalized.contains("tool not found")
                || normalized.contains("unknown method")) {
            return ToolFailureCategory.TOOL_DEFINITION_STALE;
        }
        return ToolFailureCategory.DEFAULT;
    }

    private long resolveFailureTtlMs(ToolFailureCategory category) {
        Duration ttl = switch (category) {
            case PARAM_VALIDATION -> PARAM_VALIDATION_FAILURE_TTL;
            case TRANSIENT -> TRANSIENT_FAILURE_TTL;
            case TOOL_DEFINITION_STALE, DEFAULT -> DEFAULT_FAILURE_TTL;
        };
        return Math.max(1000L, ttl.toMillis());
    }

    private boolean shouldInvalidateToolDefinitions(ToolFailureCategory category, String result) {
        if (category == ToolFailureCategory.PARAM_VALIDATION || category == ToolFailureCategory.TOOL_DEFINITION_STALE) {
            return true;
        }
        String normalized = result == null ? "" : result.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("unknown tool") || normalized.contains("tool not found")
                || normalized.contains("invalid parameter") || normalized.contains("invalid params")
                || normalized.contains("schema");
    }

    private void invalidateToolDefinitions() {
        cachedToolProviderResult = null;
        toolProviderLoadFuture.set(null);
        if (mcpClients == null || mcpClients.isEmpty()) {
            return;
        }
        for (McpClient mcpClient : mcpClients) {
            if (mcpClient instanceof PooledMcpClient pooledMcpClient) {
                pooledMcpClient.invalidateToolSpecCache();
            }
        }
    }

    private int recordConsecutiveRequest(String requestFingerprint) {
        synchronized (consecutiveRequestLock) {
            if (requestFingerprint != null && requestFingerprint.equals(lastRequestFingerprint)) {
                consecutiveSameRequestCount++;
            } else {
                lastRequestFingerprint = requestFingerprint;
                consecutiveSameRequestCount = 1;
            }
            return consecutiveSameRequestCount;
        }
    }

    private String executeWithTimeout(String toolName, ToolExecutor delegateExecutor, ToolExecutionRequest request,
            Object memoryId) {
        CompletableFuture<String> future = CompletableFuture
                .supplyAsync(() -> delegateExecutor.execute(request, memoryId), toolExecutionTaskExecutor);
        try {
            return limitToolResult(toolName, future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(false);
            logger.warn("MCP 工具执行超时: tool={}, timeoutMs={}", toolName, executionTimeout.toMillis());
            return "❌ 工具执行超时，请缩小范围后重试，或直接基于现有信息回答。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("MCP 工具执行被中断: tool={}", toolName, e);
            return "❌ 工具执行被中断，请稍后重试。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String errorMessage = buildErrorMessage(cause);
            logger.warn("MCP 工具执行失败: tool={}, error={}", toolName, errorMessage);
            if (isHttp400Error(errorMessage)) {
                logger.warn("MCP 工具参数校验失败: tool={}, args={}", toolName, abbreviateArguments(request));
                return "❌ 工具参数校验失败（HTTP 400）：请严格按照工具参数 schema 提供参数名称和类型后重试。原始错误：" + errorMessage;
            }
            return "❌ 工具执行失败：" + errorMessage;
        } catch (Exception e) {
            String errorMessage = buildErrorMessage(e);
            logger.warn("MCP 工具执行失败: tool={}, error={}", toolName, errorMessage);
            if (isHttp400Error(errorMessage)) {
                logger.warn("MCP 工具参数校验失败: tool={}, args={}", toolName, abbreviateArguments(request));
                return "❌ 工具参数校验失败（HTTP 400）：请严格按照工具参数 schema 提供参数名称和类型后重试。原始错误：" + errorMessage;
            }
            return "❌ 工具执行失败：" + errorMessage;
        }
    }

    private String buildErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        StringBuilder builder = new StringBuilder();
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 5) {
            String message = cursor.getMessage();
            if (message != null && !message.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(" <- ");
                }
                builder.append(message.trim());
            }
            cursor = cursor.getCause();
            depth++;
        }
        if (!builder.isEmpty()) {
            return builder.toString();
        }
        return throwable.getClass().getSimpleName();
    }

    private boolean isHttp400Error(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("status code: 400")
                || normalized.contains("http 400")
                || normalized.contains("bad request");
    }

    private String abbreviateArguments(ToolExecutionRequest request) {
        if (request == null || request.arguments() == null) {
            return "";
        }
        return ToolPayloadUtils.abbreviateForPayload(request.arguments(), 500);
    }

    private String limitToolResult(String toolName, String result) {
        if (result == null || result.length() <= maxResultChars) {
            return result;
        }
        logger.warn("MCP 工具结果过长，已截断: tool={}, length={}, max={}", toolName, result.length(), maxResultChars);
        String suffix = "\n\n[工具结果过长，已截断至 " + maxResultChars + " 字符；如信息不足，请缩小范围后重试。]";
        return ToolPayloadUtils.truncateToolResult(result, maxResultChars, suffix);
    }

    private String buildRequestFingerprint(String toolName, ToolExecutionRequest request) {
        String arguments = request != null && request.arguments() != null ? request.arguments() : "";
        return toolName + "::" + sha256Prefix(arguments, 16);
    }

    private String sha256Prefix(String value, int hexLength) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, Math.min(Math.max(1, hexLength), hex.length()));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private String getCachedResult(String requestFingerprint) {
        return cachedToolResults.getIfPresent(requestFingerprint);
    }

    private enum ToolFailureCategory {
        PARAM_VALIDATION, TRANSIENT, TOOL_DEFINITION_STALE, DEFAULT
    }

    private record FailedToolFingerprint(long expiresAtMillis) {
        private boolean isExpired() {
            return expiresAtMillis <= System.currentTimeMillis();
        }
    }
}
