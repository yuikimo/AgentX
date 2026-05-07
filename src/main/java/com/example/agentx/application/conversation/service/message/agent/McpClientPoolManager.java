package com.example.agentx.application.conversation.service.message.agent;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.PresetParameter;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** MCP 客户端池：按服务/用户/预设参数复用客户端，过期再关闭。 */
@Component
public class McpClientPoolManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientPoolManager.class);
    private static final long MIN_EVICT_SCAN_INTERVAL_MS = 30_000L;

    private final ConcurrentMap<String, PooledMcpClient.PooledClientHolder> clientPool = new ConcurrentHashMap<>();
    private final ChatToolProperties chatToolProperties;
    private final AtomicLong lastEvictScanAt = new AtomicLong(0L);

    public McpClientPoolManager(ChatToolProperties chatToolProperties) {
        this.chatToolProperties = chatToolProperties;
    }

    public McpClient borrowClient(String mcpServerName, String sseUrl, Map<String, Map<String, Map<String, String>>> toolPresetParams,
            String userId) {
        evictExpiredClientsIfDue();
        String cacheKey = buildCacheKey(mcpServerName, userId, toolPresetParams);
        PooledMcpClient.PooledClientHolder holder = clientPool.computeIfAbsent(cacheKey,
                ignored -> createHolder(sseUrl, resolvePresetParameters(mcpServerName, toolPresetParams)));
        PooledMcpClient pooledClient = new PooledMcpClient(holder, chatToolProperties.getMcp().getToolSpecCacheTtlMs(),
                this::evictExpiredClientsIfDue);
        evictOverflowClientsIfNeeded();
        return pooledClient;
    }

    @PreDestroy
    public void shutdown() {
        clientPool.forEach((key, holder) -> closeHolder(key, holder));
        clientPool.clear();
    }

    private PooledMcpClient.PooledClientHolder createHolder(String sseUrl, List<PresetParameter> presetParameters) {
        McpTransport transport = new HttpMcpTransport.Builder().sseUrl(sseUrl).logRequests(false).logResponses(false)
                .timeout(Duration.ofMillis(chatToolProperties.getMcp().getTransportTimeoutMs())).build();
        McpClient client = new DefaultMcpClient.Builder().transport(transport).build();
        if (presetParameters != null && !presetParameters.isEmpty()) {
            client.presetParameters(presetParameters);
        }
        return new PooledMcpClient.PooledClientHolder(client);
    }

    private List<PresetParameter> resolvePresetParameters(String mcpServerName,
            Map<String, Map<String, Map<String, String>>> toolPresetParams) {
        if (toolPresetParams == null || !toolPresetParams.containsKey(mcpServerName)) {
            return List.of();
        }
        List<PresetParameter> presetParameters = new ArrayList<>();
        Map<String, Map<String, String>> currentServerPresetParams = toolPresetParams.get(mcpServerName);
        if (currentServerPresetParams == null || currentServerPresetParams.isEmpty()) {
            return presetParameters;
        }
        new TreeMap<>(currentServerPresetParams).forEach((toolName, presetParamMap) -> {
            Map<String, String> safePresetParamMap = presetParamMap == null ? Map.of() : new TreeMap<>(presetParamMap);
            try {
                presetParameters.add(new PresetParameter(toolName, JsonUtils.toJsonString(safePresetParamMap)));
            } catch (Exception e) {
                logger.warn("序列化工具预设参数失败，已跳过: server={}, tool={}, error={}", mcpServerName, toolName,
                        e.getMessage());
            }
        });
        return presetParameters;
    }

    private String buildCacheKey(String mcpServerName, String userId,
            Map<String, Map<String, Map<String, String>>> toolPresetParams) {
        StringBuilder builder = new StringBuilder();
        builder.append(mcpServerName).append("::").append(userId == null ? "" : userId);
        Map<String, Map<String, String>> currentServerPresetParams = toolPresetParams != null
                ? toolPresetParams.get(mcpServerName)
                : null;
        if (currentServerPresetParams == null || currentServerPresetParams.isEmpty()) {
            return builder.toString();
        }
        try {
            builder.append("::").append(JsonUtils.toJsonString(normalizePresetParams(currentServerPresetParams)));
        } catch (Exception e) {
            builder.append("::preset-error");
        }
        return builder.toString();
    }

    private Map<String, Map<String, String>> normalizePresetParams(Map<String, Map<String, String>> presetParams) {
        if (presetParams == null || presetParams.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> normalized = new TreeMap<>();
        new TreeMap<>(presetParams).forEach((toolName, presetParamMap) ->
                normalized.put(toolName, presetParamMap == null ? Map.of() : new TreeMap<>(presetParamMap)));
        return normalized;
    }

    private void evictExpiredClientsIfDue() {
        long now = System.currentTimeMillis();
        long lastScanAt = lastEvictScanAt.get();
        if (now - lastScanAt < MIN_EVICT_SCAN_INTERVAL_MS) {
            return;
        }
        if (lastEvictScanAt.compareAndSet(lastScanAt, now)) {
            evictExpiredClients();
        }
    }

    @Scheduled(initialDelayString = "${chat.tools.mcp.client-pool-cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${chat.tools.mcp.client-pool-cleanup-fixed-delay-ms:60000}")
    public void evictExpiredClients() {
        clientPool.forEach((key, holder) -> {
            if (holder.canEvict(chatToolProperties.getMcp().getClientPoolTtlMs()) && clientPool.remove(key, holder)) {
                closeHolder(key, holder);
            }
        });
        evictOverflowClientsIfNeeded();
    }

    private void evictOverflowClientsIfNeeded() {
        int maxSize = Math.max(1, chatToolProperties.getMcp().getClientPoolMaxSize());
        int overflow = clientPool.size() - maxSize;
        if (overflow <= 0) {
            return;
        }
        List<Map.Entry<String, PooledMcpClient.PooledClientHolder>> idleEntries = clientPool.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isIdle())
                .sorted(Comparator.comparingLong(entry -> entry.getValue().lastAccessedAt()))
                .limit(overflow)
                .toList();
        for (Map.Entry<String, PooledMcpClient.PooledClientHolder> entry : idleEntries) {
            String key = entry.getKey();
            PooledMcpClient.PooledClientHolder holder = entry.getValue();
            if (holder != null && holder.isIdle() && clientPool.remove(key, holder)) {
                logger.info("MCP 客户端池超限，淘汰空闲连接: key={}, poolSize={}, maxSize={}", key, clientPool.size(),
                        maxSize);
                closeHolder(key, holder);
            }
        }
    }

    private void closeHolder(String key, PooledMcpClient.PooledClientHolder holder) {
        try {
            holder.closeUnderlying();
        } catch (Exception e) {
            logger.debug("关闭池化 MCP 客户端失败: key={}, error={}", key, e.getMessage());
        }
    }
}
