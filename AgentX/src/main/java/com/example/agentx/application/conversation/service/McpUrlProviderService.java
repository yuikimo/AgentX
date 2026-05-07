package com.example.agentx.application.conversation.service;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.application.container.service.ContainerAppService;
import com.example.agentx.application.container.dto.ContainerDTO;
import com.example.agentx.application.tool.service.ToolInstallTemplateService;
import com.example.agentx.domain.container.constant.ContainerStatus;
import com.example.agentx.domain.tool.service.ToolDomainService;
import com.example.agentx.domain.tool.service.ToolVersionDomainService;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.domain.tool.model.ToolVersionEntity;
import com.example.agentx.domain.tool.model.UserToolEntity;
import com.example.agentx.infrastructure.mcp_gateway.MCPGatewayService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** MCP URL提供服务 负责协调容器管理和URL构建 */
@Service
public class McpUrlProviderService {

    private static final Logger logger = LoggerFactory.getLogger(McpUrlProviderService.class);

    private final MCPGatewayService mcpGatewayService;
    private final ContainerAppService containerAppService;
    private final ToolDomainService toolDomainService;
    private final ToolVersionDomainService toolVersionDomainService;
    private final ToolInstallTemplateService toolInstallTemplateService;
    private final TaskExecutor mcpToolReadinessTaskExecutor;
    private final ChatToolProperties chatToolProperties;
    private final Map<String, CompletableFuture<Boolean>> readinessTasks = new ConcurrentHashMap<>();
    private Cache<String, Boolean> globalToolCache;
    private Cache<String, ContainerDTO> userContainerCache;
    private Cache<String, Boolean> deployedToolCache;
    private Cache<String, ContainerDTO> reviewContainerCache;
    public McpUrlProviderService(MCPGatewayService mcpGatewayService, ContainerAppService containerAppService,
            ToolDomainService toolDomainService, ToolVersionDomainService toolVersionDomainService,
            ToolInstallTemplateService toolInstallTemplateService,
            @Qualifier("mcpToolReadinessTaskExecutor") TaskExecutor mcpToolReadinessTaskExecutor,
            ChatToolProperties chatToolProperties) {
        this.mcpGatewayService = mcpGatewayService;
        this.containerAppService = containerAppService;
        this.toolDomainService = toolDomainService;
        this.toolVersionDomainService = toolVersionDomainService;
        this.toolInstallTemplateService = toolInstallTemplateService;
        this.mcpToolReadinessTaskExecutor = mcpToolReadinessTaskExecutor;
        this.chatToolProperties = chatToolProperties;
    }

    @PostConstruct
    void initializeCaches() {
        ChatToolProperties.Mcp mcpProps = chatToolProperties.getMcp();
        globalToolCache = buildCache(mcpProps.getGlobalToolCacheTtlMs(), mcpProps.getGlobalToolCacheMaxSize());
        userContainerCache = buildCache(mcpProps.getContainerReadyCacheTtlMs(),
                mcpProps.getContainerReadyCacheMaxSize());
        deployedToolCache = buildCache(mcpProps.getDeploymentCacheTtlMs(), mcpProps.getDeploymentCacheMaxSize());
        reviewContainerCache = buildCache(mcpProps.getContainerReadyCacheTtlMs(),
                mcpProps.getReviewContainerCacheMaxSize());
    }

    /** 智能获取SSE URL：自动判断工具类型并选择连接策略
     * 
     * @param mcpServerName 工具服务名称
     * @param userId 用户ID（可选，用户工具必需）
     * @return 对应的SSE连接URL */
    public String getSSEUrl(String mcpServerName, String userId) {
        // 1. 自动判断工具类型
        boolean isGlobalTool = isGlobalTool(mcpServerName, userId);

        if (isGlobalTool) {
            // 全局工具：使用审核容器
            return buildReviewContainerSSEUrl(mcpServerName, userId);
        }

        // 用户工具：需要用户容器
        return buildUserContainerSSEUrl(mcpServerName, userId);
    }

    /** 获取MCP工具的SSE URL（包含容器自动创建和启动）
     * 
     * @param mcpServerName 工具服务名称
     * @param userId 用户ID
     * @return SSE连接URL */
    public String getMcpToolUrl(String mcpServerName, String userId) {
        try {
            return getSSEUrl(mcpServerName, userId);
        } catch (Exception e) {
            logger.error("获取MCP工具URL失败: userId={}, tool={}", userId, mcpServerName, e);
            throw new BusinessException("无法连接工具：" + mcpServerName + " - " + e.getMessage());
        }
    }

    /** 判断是否为全局工具 */
    private boolean isGlobalTool(String mcpServerName, String userId) {
        String cacheKey = buildUserScopedCacheKey(mcpServerName, userId);
        Boolean cached = getCachedValue(globalToolCache, cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            ToolEntity tool = toolDomainService.getToolByServerNameForUsage(mcpServerName, userId);
            boolean isGlobal = tool != null && tool.isGlobal();
            globalToolCache.put(cacheKey, isGlobal);
            return isGlobal;
        } catch (Exception e) {
            logger.warn("无法判断工具类型，默认为用户工具: {}", mcpServerName, e);
            return false; // 默认为用户工具，需要用户容器
        }
    }

    /** 构建用户容器工具SSE URL */
    private String buildUserContainerSSEUrl(String mcpServerName, String userId) {
        try {
            logger.info("准备用户容器工具连接: userId={}, tool={}", userId, mcpServerName);

            // 1. 确保用户容器就绪（自动创建和启动）
            ContainerDTO containerInfo = ensureUserContainerReady(userId);

            // 2. 构建容器SSE URL
            String sseUrl = mcpGatewayService.buildUserContainerUrl(mcpServerName, containerInfo.getIpAddress(),
                    containerInfo.getExternalPort());

            // 3. 部署工具
            deployTool(containerInfo, mcpServerName, userId, sseUrl);

            logger.info("用户容器工具连接就绪: userId={}, url={}", userId, maskSensitiveInfo(sseUrl));
            return sseUrl;

        } catch (Exception e) {
            logger.error("构建用户容器SSE URL失败: userId={}, tool={}", userId, mcpServerName, e);
            throw new BusinessException("无法连接用户工具：" + e.getMessage());
        }
    }

    /** 构建审核容器工具SSE URL */
    private String buildReviewContainerSSEUrl(String mcpServerName, String userId) {
        try {
            logger.info("准备审核容器工具连接: tool={}", mcpServerName);

            // 1. 确保审核容器就绪（自动创建和启动）
            ContainerDTO containerInfo = ensureReviewContainerReady();

            // 2. 构建容器SSE URL
            String sseUrl = mcpGatewayService.buildUserContainerUrl(mcpServerName, containerInfo.getIpAddress(),
                    containerInfo.getExternalPort());

            // 3. 部署全局工具（按需安装到审核容器）
            deployGlobalTool(containerInfo, mcpServerName, userId, sseUrl);

            logger.info("审核容器工具连接就绪: tool={}, url={}", mcpServerName, maskSensitiveInfo(sseUrl));
            return sseUrl;

        } catch (Exception e) {
            logger.error("构建审核容器SSE URL失败: tool={}", mcpServerName, e);
            throw new BusinessException("无法连接全局工具：" + e.getMessage());
        }
    }

    /** 确保用户容器就绪（自动创建和启动） */
    private ContainerDTO ensureUserContainerReady(String userId) {
        ContainerDTO cachedContainer = getCachedValue(userContainerCache, userId);
        if (cachedContainer != null) {
            return cachedContainer;
        }
        try {
            // ContainerAppService.getUserContainer() 已经包含自动创建和启动逻辑
            ContainerDTO userContainer = containerAppService.getUserContainer(userId);

            // 最终验证容器状态
            if (!isContainerHealthy(userContainer)) {
                throw new BusinessException("用户容器准备失败，状态异常: " + userContainer.getStatus());
            }

            userContainerCache.put(userId, userContainer);
            return userContainer;
        } catch (Exception e) {
            logger.error("准备用户容器失败: userId={}", userId, e);
            throw new BusinessException("用户容器准备失败: " + e.getMessage());
        }
    }

    /** 检查容器是否健康（使用统一的健康检查机制） 注意：此方法基于DTO进行基础检查，详细的Docker状态检查由ContainerAppService负责 */
    private boolean isContainerHealthy(ContainerDTO container) {
        if (container == null) {
            return false;
        }

        // 检查容器状态是否为运行中
        boolean isRunning = ContainerStatus.RUNNING.equals(container.getStatus());

        // 检查必要的网络信息是否存在
        boolean hasNetworkInfo = container.getIpAddress() != null && container.getExternalPort() != null;

        // 检查Docker容器ID是否存在（基础验证）
        boolean hasDockerContainerId = container.getDockerContainerId() != null;

        boolean basicHealthy = isRunning && hasNetworkInfo && hasDockerContainerId;

        if (!basicHealthy) {
            logger.warn("容器基础健康检查失败: containerId={}, running={}, networkInfo={}, dockerId={}", container.getId(),
                    isRunning, hasNetworkInfo, hasDockerContainerId);
        }

        return basicHealthy;
    }

    /** 部署工具到用户容器 */
    private void deployTool(ContainerDTO container, String toolName, String userId, String sseUrl) {
        String deploymentCacheKey = buildDeploymentCacheKey(buildUserScopedCacheKey(toolName, userId), container);
        Boolean deployed = getCachedValue(deployedToolCache, deploymentCacheKey);
        if (Boolean.TRUE.equals(deployed)) {
            return;
        }
        try {
            UserToolEntity userTool = toolDomainService.getUserInstalledToolByServerName(toolName, userId);
            if (userTool == null) {
                throw new BusinessException("用户未安装工具: " + toolName);
            }

            String installCommandJson = buildUserInstallCommand(userTool);
            try {
                mcpGatewayService.deployTool(installCommandJson, container.getIpAddress(), container.getExternalPort());
            } catch (Exception deployException) {
                // 部署 API 报错时仍尝试检查就绪状态，避免“已部署但部署请求超时/失败”造成误判。
                if (isTimeoutException(deployException)) {
                    logger.warn("用户工具部署请求超时，继续轮询就绪状态: tool={}, error={}", toolName,
                            deployException.getMessage());
                } else {
                    logger.warn("用户工具部署请求失败，继续轮询就绪状态: tool={}, error={}", toolName,
                            deployException.getMessage());
                }
            }

            CompletableFuture<Boolean> readinessTask = getOrStartReadinessTask(deploymentCacheKey, sseUrl, toolName);
            boolean ready = awaitToolReady(readinessTask, toolName);
            if (!ready) {
                throw new BusinessException("用户工具部署后就绪超时: " + toolName);
            }
            logger.debug("工具 {} 部署请求已发送到用户容器", toolName);

        } catch (Exception e) {
            logger.warn("部署容器内工具失败，本轮将标记为不可用: tool={}, error={}", toolName, e.getMessage());
            throw e instanceof BusinessException ? (BusinessException) e : new BusinessException(e.getMessage(), e);
        }
    }

    /** 将工具安装命令转换为JSON字符串 */
    private String convertInstallCommand(Map<String, Object> installCommand) {
        try {
            return JsonUtils.toJsonString(installCommand);
        } catch (Exception e) {
            throw new BusinessException("转换安装命令失败: " + e.getMessage());
        }
    }

    /** 确保审核容器就绪（自动创建和启动） */
    private ContainerDTO ensureReviewContainerReady() {
        ContainerDTO cachedContainer = getCachedValue(reviewContainerCache, "review");
        if (cachedContainer != null) {
            return cachedContainer;
        }
        try {
            // ContainerAppService.getReviewContainer() 已经包含自动创建和启动逻辑
            ContainerDTO reviewContainer = containerAppService.getOrCreateReviewContainer();

            // 最终验证容器状态
            if (!isContainerHealthy(reviewContainer)) {
                throw new BusinessException("审核容器准备失败，状态异常: " + reviewContainer.getStatus());
            }

            reviewContainerCache.put("review", reviewContainer);
            return reviewContainer;
        } catch (Exception e) {
            logger.error("准备审核容器失败", e);
            throw new BusinessException("审核容器准备失败: " + e.getMessage());
        }
    }

    /** 部署全局工具到审核容器 */
    private void deployGlobalTool(ContainerDTO container, String toolName, String userId, String sseUrl) {
        String deploymentCacheKey = buildDeploymentCacheKey("global::" + toolName, container);
        Boolean deployed = getCachedValue(deployedToolCache, deploymentCacheKey);
        if (Boolean.TRUE.equals(deployed)) {
            return;
        }
        try {
            UserToolEntity userTool = toolDomainService.getUserInstalledToolByServerName(toolName, userId);
            if (userTool == null) {
                logger.warn("无法找到全局工具定义，跳过审核容器部署: tool={}, userId={}", toolName, userId);
                return;
            }

            String installCommandJson = buildUserInstallCommand(userTool);
            try {
                mcpGatewayService.deployTool(installCommandJson, container.getIpAddress(), container.getExternalPort());
            } catch (Exception deployException) {
                if (isTimeoutException(deployException)) {
                    logger.warn("全局工具部署请求超时，继续轮询就绪状态: tool={}, error={}", toolName,
                            deployException.getMessage());
                } else {
                    throw deployException;
                }
            }

            CompletableFuture<Boolean> readinessTask = getOrStartReadinessTask(deploymentCacheKey, sseUrl, toolName);
            boolean ready = awaitToolReady(readinessTask, toolName);
            if (!ready) {
                throw new BusinessException("全局工具部署后就绪超时: " + toolName);
            }
            logger.debug("全局工具 {} 已部署到审核容器", toolName);

        } catch (Exception e) {
            logger.error("部署全局工具到审核容器失败: tool={}, error={}", toolName, e.getMessage(), e);
            throw new BusinessException("全局工具部署失败: " + e.getMessage());
        }
    }

    /** 屏蔽敏感信息 */
    private String maskSensitiveInfo(String url) {
        if (url == null)
            return null;
        return url.replaceAll("api_key=[^&]*", "api_key=***");
    }

    private String buildUserInstallCommand(UserToolEntity userTool) {
        ToolVersionEntity toolVersion = toolVersionDomainService.getToolVersion(userTool.getToolId(),
                userTool.getVersion());

        ToolEntity sourceTool = toolDomainService.getTool(userTool.getToolId());
        if (sourceTool.getUserId().equals(userTool.getUserId())) {
            return convertInstallCommand(sourceTool.getInstallCommand());
        }

        if (toolInstallTemplateService.requiresSensitiveUserConfig(toolVersion.getInstallFields())
                && !ToolInstallTemplateService.STATUS_CONFIGURED.equals(userTool.getConfigStatus())) {
            throw new BusinessException("工具未配置安装变量，请先完成工具配置: " + userTool.getMcpServerName());
        }
        if (toolVersion.getInstallTemplate() == null || toolVersion.getInstallTemplate().isEmpty()) {
            throw new BusinessException("工具版本未配置公开安装模板: " + userTool.getMcpServerName());
        }
        Map<String, Object> installValues = toolInstallTemplateService.decryptInstallValues(userTool.getInstallValues());
        Map<String, Object> renderedInstallCommand = toolInstallTemplateService
                .renderInstallCommand(toolVersion.getInstallTemplate(), toolVersion.getInstallFields(), installValues);
        return convertInstallCommand(renderedInstallCommand);
    }

    private CompletableFuture<Boolean> getOrStartReadinessTask(String deploymentCacheKey, String sseUrl, String toolName) {
        return readinessTasks.computeIfAbsent(deploymentCacheKey, ignored -> {
            CompletableFuture<Boolean> future = CompletableFuture
                    .supplyAsync(() -> pollToolReady(sseUrl, toolName), mcpToolReadinessTaskExecutor);
            future.whenComplete((ready, throwable) -> {
                readinessTasks.remove(deploymentCacheKey, future);
                if (throwable != null) {
                    logger.warn("后台确认工具就绪失败: tool={}, error={}", toolName, throwable.getMessage());
                    return;
                }
                if (Boolean.TRUE.equals(ready)) {
                    deployedToolCache.put(deploymentCacheKey, true);
                    logger.debug("工具后台就绪确认成功: tool={}", toolName);
                } else {
                    logger.warn("工具后台就绪确认超时或失败: tool={}", toolName);
                }
            });
            return future;
        });
    }

    private boolean awaitToolReady(CompletableFuture<Boolean> readinessTask, String toolName) {
        if (readinessTask == null) {
            return false;
        }
        try {
            ChatToolProperties.Mcp mcpProps = chatToolProperties.getMcp();
            return Boolean.TRUE.equals(readinessTask.get(
                    Math.max(mcpProps.getReadinessTimeoutMs(), mcpProps.getReadinessPollIntervalMs()),
                    TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            logger.warn("等待工具异步就绪超时: tool={}, timeoutMs={}", toolName,
                    chatToolProperties.getMcp().getReadinessTimeoutMs());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("等待工具异步就绪被中断: tool={}", toolName);
            return false;
        } catch (Exception e) {
            logger.warn("等待工具异步就绪失败: tool={}, error={}", toolName, e.getMessage());
            return false;
        }
    }

    private boolean pollToolReady(String sseUrl, String toolName) {
        ChatToolProperties.Mcp mcpProps = chatToolProperties.getMcp();
        long deadline = System.currentTimeMillis()
                + Math.max(mcpProps.getReadinessTimeoutMs(), mcpProps.getReadinessPollIntervalMs());
        long pollIntervalMs = Math.max(50L, mcpProps.getReadinessPollIntervalMs());
        long maxPollIntervalMs = Math.max(pollIntervalMs, 2000L);
        while (System.currentTimeMillis() < deadline) {
            if (checkToolReady(sseUrl)) {
                return true;
            }
            try {
                long remainingMs = Math.max(0L, deadline - System.currentTimeMillis());
                Thread.sleep(Math.min(pollIntervalMs, remainingMs));
                pollIntervalMs = Math.min(maxPollIntervalMs, pollIntervalMs * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("等待工具就绪被中断: tool={}", toolName);
                return false;
            }
        }
        logger.warn("工具部署后未在等待窗口内完成就绪确认: tool={}", toolName);
        return false;
    }

    private boolean checkToolReady(String sseUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(sseUrl);
            if (!isTcpPortOpen(url)) {
                return false;
            }
            connection = (HttpURLConnection) url.openConnection();
            int connectTimeout = (int) Math.min(2000L,
                    Math.max(200L, chatToolProperties.getMcp().getReadinessCheckTimeoutMs()));
            int readTimeout = (int) Math.max(200L, chatToolProperties.getMcp().getReadinessCheckTimeoutMs());
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/event-stream");
            int statusCode = connection.getResponseCode();
            return statusCode >= 200 && statusCode < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isTcpPortOpen(URL url) {
        if (url == null || url.getHost() == null || url.getHost().isBlank()) {
            return false;
        }
        int port = url.getPort();
        if (port <= 0) {
            port = url.getDefaultPort();
        }
        if (port <= 0) {
            return false;
        }
        int timeout = (int) Math.min(1000L, Math.max(100L, chatToolProperties.getMcp().getReadinessCheckTimeoutMs()));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(url.getHost(), port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        String message = throwable.getMessage();
        return message != null
                && (message.contains("Read timed out") || message.contains("timeout") || message.contains("Timeout"));
    }

    private <T> Cache<String, T> buildCache(long ttlMs, long maximumSize) {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(Math.max(1L, ttlMs), TimeUnit.MILLISECONDS)
                .maximumSize(Math.max(1L, maximumSize))
                .recordStats()
                .build();
    }

    private <T> T getCachedValue(Cache<String, T> cache, String key) {
        if (cache == null || key == null) {
            return null;
        }
        return cache.getIfPresent(key);
    }

    private String buildUserScopedCacheKey(String value, String userId) {
        return value + "::" + (userId == null ? "" : userId);
    }

    private String buildDeploymentCacheKey(String baseKey, ContainerDTO container) {
        String containerVersion = "unknown-container";
        if (container != null) {
            if (container.getDockerContainerId() != null && !container.getDockerContainerId().isBlank()) {
                containerVersion = container.getDockerContainerId();
            } else if (container.getId() != null && !container.getId().isBlank()) {
                containerVersion = container.getId();
            }
            if (container.getUpdatedAt() != null) {
                containerVersion += "@" + container.getUpdatedAt();
            }
        }
        return baseKey + "::container::" + containerVersion;
    }

    @Scheduled(initialDelayString = "${chat.tools.mcp.cache-cleanup-initial-delay-ms:600000}", fixedDelayString = "${chat.tools.mcp.cache-cleanup-fixed-delay-ms:600000}")
    public void cleanupCaches() {
        if (globalToolCache != null) {
            globalToolCache.cleanUp();
        }
        if (userContainerCache != null) {
            userContainerCache.cleanUp();
        }
        if (deployedToolCache != null) {
            deployedToolCache.cleanUp();
        }
        if (reviewContainerCache != null) {
            reviewContainerCache.cleanUp();
        }
    }
}
