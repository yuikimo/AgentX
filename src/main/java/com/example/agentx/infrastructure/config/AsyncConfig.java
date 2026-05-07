package com.example.agentx.infrastructure.config;

import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.domain.memory.config.MemoryExtractProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** 异步配置 启用Spring的异步处理功能，用于异步事件处理 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private final ChatToolProperties chatToolProperties;
    private final ChatContextProperties chatContextProperties;
    private final MemoryExtractProperties memoryExtractProperties;

    public AsyncConfig(ChatToolProperties chatToolProperties, ChatContextProperties chatContextProperties,
            MemoryExtractProperties memoryExtractProperties) {
        this.chatToolProperties = chatToolProperties;
        this.chatContextProperties = chatContextProperties;
        this.memoryExtractProperties = memoryExtractProperties;
    }

    /** 兼容旧注入名：用于低优先级记忆异步任务。 */
    @Bean(name = "memoryTaskExecutor")
    public ThreadPoolTaskExecutor memoryTaskExecutor() {
        return buildMemoryExecutor("memory-async-", 2, 8, 200, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 专用于对话前记忆召回，队列拥塞时快速降级，避免阻塞主对话链路。 */
    @Bean(name = "memoryRecallTaskExecutor")
    public ThreadPoolTaskExecutor memoryRecallTaskExecutor() {
        return buildMemoryExecutor("memory-recall-", 2, 4, 120, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 专用于对话后记忆抽取与持久化，避免慢模型调用挤占召回线程。 */
    @Bean(name = "memoryExtractTaskExecutor")
    public ThreadPoolTaskExecutor memoryExtractTaskExecutor() {
        return buildMemoryExecutor("memory-extract-", 2, 6, 300, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 专用于会话摘要刷新，避免 token 溢出时在请求线程上等待模型调用。 */
    @Bean(name = "summaryTaskExecutor")
    public ThreadPoolTaskExecutor summaryTaskExecutor() {
        return buildMemoryExecutor("summary-async-", 1, 4, 100, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "applicationEventTaskExecutor")
    public ThreadPoolTaskExecutor applicationEventTaskExecutor() {
        return buildMemoryExecutor("app-event-", 2, 8, 500, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "memoryExtractBatchFlushTaskScheduler")
    public ThreadPoolTaskScheduler memoryExtractBatchFlushTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, memoryExtractProperties.getSchedulerPoolSize()));
        scheduler.setThreadNamePrefix("memory-extract-batch-flush-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    private ThreadPoolTaskExecutor buildMemoryExecutor(String threadNamePrefix, int corePoolSize, int maxPoolSize,
            int queueCapacity, java.util.concurrent.RejectedExecutionHandler rejectedExecutionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(rejectedExecutionHandler);
        executor.initialize();
        return executor;
    }

    /** 专用于知识库嵌入迁移重建，避免阻塞记忆线程池 */
    @Bean(name = "embeddingMigrationTaskExecutor")
    public ThreadPoolTaskExecutor embeddingMigrationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("embedding-migrate-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 专用于会话智能命名，避免每次请求直接创建线程 */
    @Bean(name = "sessionRenameTaskExecutor")
    public ThreadPoolTaskExecutor sessionRenameTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("session-rename-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "attachmentProcessingTaskExecutor")
    public ThreadPoolTaskExecutor attachmentProcessingTaskExecutor() {
        ChatContextProperties.ProcessingExecutor props = chatContextProperties.getAttachment().getProcessingExecutor();
        return buildMemoryExecutor("attachment-process-", Math.max(1, props.getCorePoolSize()),
                Math.max(1, props.getMaxPoolSize()), Math.max(1, props.getQueueCapacity()),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 专用于多 embedding profile 的分组检索并发执行 */
    @Bean(name = "ragSearchGroupTaskExecutor")
    public ThreadPoolTaskExecutor ragSearchGroupTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-search-group-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "mcpClientInitTaskExecutor")
    public ThreadPoolTaskExecutor mcpClientInitTaskExecutor() {
        ChatToolProperties.Pool props = chatToolProperties.getExecutors().getMcpInit();
        return buildToolExecutor("mcp-client-init-", props, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "mcpToolExecutionTaskExecutor")
    public ThreadPoolTaskExecutor mcpToolExecutionTaskExecutor() {
        ChatToolProperties.Pool props = chatToolProperties.getExecutors().getMcpExecution();
        return buildToolExecutor("managed-mcp-tool-", props, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "builtInToolExecutionTaskExecutor")
    public ThreadPoolTaskExecutor builtInToolExecutionTaskExecutor() {
        ChatToolProperties.Pool props = chatToolProperties.getExecutors().getBuiltInExecution();
        return buildToolExecutor("built-in-tool-", props, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "mcpToolReadinessTaskExecutor")
    public ThreadPoolTaskExecutor mcpToolReadinessTaskExecutor() {
        ChatToolProperties.Pool props = chatToolProperties.getExecutors().getMcpReadiness();
        return buildToolExecutor("mcp-tool-ready-", props, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "toolExecutionProgressTaskScheduler")
    public ThreadPoolTaskScheduler toolExecutionProgressTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, chatToolProperties.getExecutors().getProgress().getPoolSize()));
        scheduler.setThreadNamePrefix("tool-progress-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    private ThreadPoolTaskExecutor buildToolExecutor(String threadNamePrefix, ChatToolProperties.Pool props,
            java.util.concurrent.RejectedExecutionHandler rejectedExecutionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, props.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), props.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, props.getQueueCapacity()));
        executor.setKeepAliveSeconds(Math.max(30, props.getKeepAliveSeconds()));
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(rejectedExecutionHandler);
        executor.initialize();
        return executor;
    }
}
