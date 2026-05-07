package com.example.agentx.application.tool.service.state.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.application.container.service.ReviewContainerService;
import com.example.agentx.application.tool.service.state.AppToolStateProcessor;
import com.example.agentx.domain.tool.constant.ToolStatus;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.domain.tool.model.config.ToolDefinition;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.mcp_gateway.MCPGatewayService;

import java.util.List;
import java.util.Map;

/** 应用层获取工具列表处理器
 * 
 * 职责： 1. 从审核容器获取工具定义列表 2. 调用MCPGatewayService和ReviewContainerService 3. 将工具定义存储到工具实体 4. 转换到手动审核状态 */
public class AppFetchingToolsProcessor implements AppToolStateProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AppFetchingToolsProcessor.class);
    private static final long READINESS_TIMEOUT_MS = 60000L;
    private static final long READINESS_POLL_INTERVAL_MS = 2000L;
    private static final int READINESS_ATTEMPT_TIMEOUT_MS = 5000;

    private final MCPGatewayService mcpGatewayService;
    private final ReviewContainerService reviewContainerService;

    /** 构造函数，注入依赖服务
     * 
     * @param mcpGatewayService MCP网关服务
     * @param reviewContainerService 审核容器服务 */
    public AppFetchingToolsProcessor(MCPGatewayService mcpGatewayService,
            ReviewContainerService reviewContainerService) {
        this.mcpGatewayService = mcpGatewayService;
        this.reviewContainerService = reviewContainerService;
    }

    @Override
    public ToolStatus getStatus() {
        return ToolStatus.FETCHING_TOOLS;
    }

    @Override
    public void process(ToolEntity tool) {
        logger.info("工具ID: {} 进入FETCHING_TOOLS状态，开始从审核容器获取工具列表。", tool.getId());

        try {
            String toolName = resolveToolName(tool);

            // 存储MCP服务器名称到工具实体
            tool.setMcpServerName(toolName);

            // 获取审核容器连接信息
            logger.info("获取审核容器连接信息用于工具 {} 的审核", toolName);
            ReviewContainerService.ReviewContainerConnection reviewConnection = reviewContainerService
                    .getReviewContainerConnection();

            logger.info("从审核容器 {}:{} 获取工具 {} 的列表", reviewConnection.getIpAddress(), reviewConnection.getPort(),
                    toolName);

            // 轮询等待MCP服务真正可用，替代固定等待，避免服务已就绪仍空等或慢启动时过早失败。
            List<ToolDefinition> toolDefinitions = waitForToolDefinitions(tool, toolName, reviewConnection);

            if (toolDefinitions != null && !toolDefinitions.isEmpty()) {
                logger.info("从审核容器获取工具列表成功，数量: {}, 工具: {}", toolDefinitions.size(), toolName);

                // 将获取到的工具定义列表设置到ToolEntity中
                tool.setToolList(toolDefinitions);
            } else {
                logger.warn("从审核容器获取工具列表失败或为空: {}", toolName);
                throw new BusinessException("从审核容器获取工具列表失败或为空");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态
            logger.error("获取工具列表过程中被中断: tool={}", tool.getName(), e);
            throw new BusinessException("获取工具列表过程中被中断: " + e.getMessage(), e);
        } catch (BusinessException e) {
            logger.error("从审核容器获取工具列表失败 {} (ID: {}): {}", tool.getName(), tool.getId(), e.getMessage(), e);
            throw e; // 重新抛出BusinessException
        } catch (Exception e) {
            logger.error("从审核容器获取工具列表 {} (ID: {}) 过程中发生意外错误: {}", tool.getName(), tool.getId(), e.getMessage(), e);
            throw new BusinessException("从审核容器获取工具列表过程中发生意外错误: " + e.getMessage(), e);
        }
    }

    @Override
    public ToolStatus getNextStatus() {
        return ToolStatus.MANUAL_REVIEW;
    }

    private String resolveToolName(ToolEntity tool) {
        // 从installCommand中获取工具名称
        Map<String, Object> installCommand = tool.getInstallCommand();
        if (installCommand == null || installCommand.isEmpty()) {
            throw new BusinessException("安装命令为空");
        }

        // 解析mcpServers中的第一个key作为工具名称
        @SuppressWarnings("unchecked")
        Map<String, Object> mcpServers = (Map<String, Object>) installCommand.get("mcpServers");
        if (mcpServers == null || mcpServers.isEmpty()) {
            throw new BusinessException("工具ID: " + tool.getId() + " 安装命令中mcpServers为空。");
        }

        // 获取第一个key作为工具名称
        String toolName = mcpServers.keySet().iterator().next();
        if (toolName == null || toolName.isEmpty()) {
            throw new BusinessException("工具ID: " + tool.getId() + " 无法从安装命令中获取工具名称。");
        }
        return toolName;
    }

    private List<ToolDefinition> waitForToolDefinitions(ToolEntity tool, String toolName,
            ReviewContainerService.ReviewContainerConnection reviewConnection) throws InterruptedException {
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_MS;
        int attempt = 1;
        Exception lastException = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                logger.info("检查审核容器工具服务就绪状态: toolId={}, toolName={}, attempt={}, timeoutMs={}", tool.getId(),
                        toolName, attempt, READINESS_ATTEMPT_TIMEOUT_MS);

                List<ToolDefinition> toolDefinitions = mcpGatewayService.listToolsFromReviewContainer(toolName,
                        reviewConnection.getIpAddress(), reviewConnection.getPort(), READINESS_ATTEMPT_TIMEOUT_MS);

                if (toolDefinitions != null && !toolDefinitions.isEmpty()) {
                    return toolDefinitions;
                }

                logger.warn("审核容器工具服务已响应但工具列表为空: toolId={}, toolName={}, attempt={}", tool.getId(), toolName,
                        attempt);
            } catch (Exception e) {
                lastException = e;
                logger.warn("审核容器工具服务尚未就绪: toolId={}, toolName={}, attempt={}, reason={}", tool.getId(), toolName,
                        attempt, e.getMessage());
            }

            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs <= 0) {
                break;
            }
            Thread.sleep(Math.min(READINESS_POLL_INTERVAL_MS, remainingMs));
            attempt++;
        }

        String lastError = lastException == null ? "工具列表为空" : lastException.getMessage();
        throw new BusinessException("审核容器工具服务未在 " + READINESS_TIMEOUT_MS + "ms 内就绪，工具: " + toolName
                + "，最后错误: " + lastError);
    }
}
