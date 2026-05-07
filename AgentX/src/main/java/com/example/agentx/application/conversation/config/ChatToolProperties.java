package com.example.agentx.application.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Tool runtime defaults. These are tuning knobs and do not need to be listed in application.yml. */
@ConfigurationProperties(prefix = "chat.tools")
public class ChatToolProperties {

    private int maxCalls = 20;
    private int maxCallsPerTool = 7;
    private long resultCacheTtlMs = 60000;
    private boolean shareToolCallCounter = false;
    private boolean includeCatalogPrompt = false;
    private long catalogCacheTtlMs = 30000;
    private int recentContextBudgetTokens = 600;
    private int recentContextMaxItems = 16;
    private final Progress progress = new Progress();
    private final BuiltIn builtIn = new BuiltIn();
    private final Mcp mcp = new Mcp();
    private final Rag rag = new Rag();
    private final Executors executors = new Executors();

    public int getMaxCalls() {
        return maxCalls;
    }

    public void setMaxCalls(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    public int getMaxCallsPerTool() {
        return maxCallsPerTool;
    }

    public void setMaxCallsPerTool(int maxCallsPerTool) {
        this.maxCallsPerTool = maxCallsPerTool;
    }

    public long getResultCacheTtlMs() {
        return resultCacheTtlMs;
    }

    public void setResultCacheTtlMs(long resultCacheTtlMs) {
        this.resultCacheTtlMs = resultCacheTtlMs;
    }

    public boolean isShareToolCallCounter() {
        return shareToolCallCounter;
    }

    public void setShareToolCallCounter(boolean shareToolCallCounter) {
        this.shareToolCallCounter = shareToolCallCounter;
    }

    public boolean isIncludeCatalogPrompt() {
        return includeCatalogPrompt;
    }

    public void setIncludeCatalogPrompt(boolean includeCatalogPrompt) {
        this.includeCatalogPrompt = includeCatalogPrompt;
    }

    public long getCatalogCacheTtlMs() {
        return catalogCacheTtlMs;
    }

    public void setCatalogCacheTtlMs(long catalogCacheTtlMs) {
        this.catalogCacheTtlMs = catalogCacheTtlMs;
    }

    public int getRecentContextBudgetTokens() {
        return recentContextBudgetTokens;
    }

    public void setRecentContextBudgetTokens(int recentContextBudgetTokens) {
        this.recentContextBudgetTokens = recentContextBudgetTokens;
    }

    public int getRecentContextMaxItems() {
        return recentContextMaxItems;
    }

    public void setRecentContextMaxItems(int recentContextMaxItems) {
        this.recentContextMaxItems = recentContextMaxItems;
    }

    public Progress getProgress() {
        return progress;
    }

    public BuiltIn getBuiltIn() {
        return builtIn;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Rag getRag() {
        return rag;
    }

    public Executors getExecutors() {
        return executors;
    }

    public static class Progress {
        private long tickIntervalMs = 1000;

        public long getTickIntervalMs() {
            return tickIntervalMs;
        }

        public void setTickIntervalMs(long tickIntervalMs) {
            this.tickIntervalMs = tickIntervalMs;
        }
    }

    public static class BuiltIn {
        private long executionTimeoutMs = 60000;
        private long definitionCacheTtlMs = 30000;

        public long getExecutionTimeoutMs() {
            return executionTimeoutMs;
        }

        public void setExecutionTimeoutMs(long executionTimeoutMs) {
            this.executionTimeoutMs = executionTimeoutMs;
        }

        public long getDefinitionCacheTtlMs() {
            return definitionCacheTtlMs;
        }

        public void setDefinitionCacheTtlMs(long definitionCacheTtlMs) {
            this.definitionCacheTtlMs = definitionCacheTtlMs;
        }
    }

    public static class Mcp {
        private long initTimeoutMs = 12000;
        private long initGraceMs = 8000;
        private long negativeCacheTtlMs = 30000;
        private long executionTimeoutMs = 60000;
        private long transportTimeoutMs = 30000;
        private long clientPoolTtlMs = 180000;
        private int clientPoolMaxSize = 5000;
        private long toolSpecCacheTtlMs = 60000;
        private int maxResultChars = 6000;
        private List<String> cacheableToolNamePatterns = new ArrayList<>(List.of("get", "list", "read", "search",
                "query", "fetch", "find", "lookup", "describe"));
        private List<String> nonCacheableToolNamePatterns = new ArrayList<>(List.of("time", "now", "current",
                "random", "create", "update", "delete", "remove", "send", "write", "execute", "run", "start",
                "stop", "restart"));
        private long globalToolCacheTtlMs = 300000;
        private long globalToolCacheMaxSize = 2048;
        private long containerReadyCacheTtlMs = 300000;
        private long containerReadyCacheMaxSize = 1024;
        private long deploymentCacheTtlMs = 300000;
        private long deploymentCacheMaxSize = 4096;
        private long reviewContainerCacheMaxSize = 8;
        private long readinessTimeoutMs = 15000;
        private long readinessPollIntervalMs = 200;
        private long readinessCheckTimeoutMs = 5000;

        public long getInitTimeoutMs() {
            return initTimeoutMs;
        }

        public void setInitTimeoutMs(long initTimeoutMs) {
            this.initTimeoutMs = initTimeoutMs;
        }

        public long getInitGraceMs() {
            return initGraceMs;
        }

        public void setInitGraceMs(long initGraceMs) {
            this.initGraceMs = initGraceMs;
        }

        public long getNegativeCacheTtlMs() {
            return negativeCacheTtlMs;
        }

        public void setNegativeCacheTtlMs(long negativeCacheTtlMs) {
            this.negativeCacheTtlMs = negativeCacheTtlMs;
        }

        public long getExecutionTimeoutMs() {
            return executionTimeoutMs;
        }

        public void setExecutionTimeoutMs(long executionTimeoutMs) {
            this.executionTimeoutMs = executionTimeoutMs;
        }

        public long getTransportTimeoutMs() {
            return transportTimeoutMs;
        }

        public void setTransportTimeoutMs(long transportTimeoutMs) {
            this.transportTimeoutMs = transportTimeoutMs;
        }

        public long getClientPoolTtlMs() {
            return clientPoolTtlMs;
        }

        public void setClientPoolTtlMs(long clientPoolTtlMs) {
            this.clientPoolTtlMs = clientPoolTtlMs;
        }

        public int getClientPoolMaxSize() {
            return clientPoolMaxSize;
        }

        public void setClientPoolMaxSize(int clientPoolMaxSize) {
            this.clientPoolMaxSize = clientPoolMaxSize;
        }

        public long getToolSpecCacheTtlMs() {
            return toolSpecCacheTtlMs;
        }

        public void setToolSpecCacheTtlMs(long toolSpecCacheTtlMs) {
            this.toolSpecCacheTtlMs = toolSpecCacheTtlMs;
        }

        public int getMaxResultChars() {
            return maxResultChars;
        }

        public void setMaxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
        }

        public List<String> getCacheableToolNamePatterns() {
            return cacheableToolNamePatterns;
        }

        public void setCacheableToolNamePatterns(List<String> cacheableToolNamePatterns) {
            this.cacheableToolNamePatterns = cacheableToolNamePatterns;
        }

        public List<String> getNonCacheableToolNamePatterns() {
            return nonCacheableToolNamePatterns;
        }

        public void setNonCacheableToolNamePatterns(List<String> nonCacheableToolNamePatterns) {
            this.nonCacheableToolNamePatterns = nonCacheableToolNamePatterns;
        }

        public long getGlobalToolCacheTtlMs() {
            return globalToolCacheTtlMs;
        }

        public void setGlobalToolCacheTtlMs(long globalToolCacheTtlMs) {
            this.globalToolCacheTtlMs = globalToolCacheTtlMs;
        }

        public long getGlobalToolCacheMaxSize() {
            return globalToolCacheMaxSize;
        }

        public void setGlobalToolCacheMaxSize(long globalToolCacheMaxSize) {
            this.globalToolCacheMaxSize = globalToolCacheMaxSize;
        }

        public long getContainerReadyCacheTtlMs() {
            return containerReadyCacheTtlMs;
        }

        public void setContainerReadyCacheTtlMs(long containerReadyCacheTtlMs) {
            this.containerReadyCacheTtlMs = containerReadyCacheTtlMs;
        }

        public long getContainerReadyCacheMaxSize() {
            return containerReadyCacheMaxSize;
        }

        public void setContainerReadyCacheMaxSize(long containerReadyCacheMaxSize) {
            this.containerReadyCacheMaxSize = containerReadyCacheMaxSize;
        }

        public long getDeploymentCacheTtlMs() {
            return deploymentCacheTtlMs;
        }

        public void setDeploymentCacheTtlMs(long deploymentCacheTtlMs) {
            this.deploymentCacheTtlMs = deploymentCacheTtlMs;
        }

        public long getDeploymentCacheMaxSize() {
            return deploymentCacheMaxSize;
        }

        public void setDeploymentCacheMaxSize(long deploymentCacheMaxSize) {
            this.deploymentCacheMaxSize = deploymentCacheMaxSize;
        }

        public long getReviewContainerCacheMaxSize() {
            return reviewContainerCacheMaxSize;
        }

        public void setReviewContainerCacheMaxSize(long reviewContainerCacheMaxSize) {
            this.reviewContainerCacheMaxSize = reviewContainerCacheMaxSize;
        }

        public long getReadinessTimeoutMs() {
            return readinessTimeoutMs;
        }

        public void setReadinessTimeoutMs(long readinessTimeoutMs) {
            this.readinessTimeoutMs = readinessTimeoutMs;
        }

        public long getReadinessPollIntervalMs() {
            return readinessPollIntervalMs;
        }

        public void setReadinessPollIntervalMs(long readinessPollIntervalMs) {
            this.readinessPollIntervalMs = readinessPollIntervalMs;
        }

        public long getReadinessCheckTimeoutMs() {
            return readinessCheckTimeoutMs;
        }

        public void setReadinessCheckTimeoutMs(long readinessCheckTimeoutMs) {
            this.readinessCheckTimeoutMs = readinessCheckTimeoutMs;
        }
    }

    public static class Rag {
        private int maxResultChars = 4000;
        private int maxResultItems = 8;
        private int maxItemChars = 500;

        public int getMaxResultChars() {
            return maxResultChars;
        }

        public void setMaxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
        }

        public int getMaxResultItems() {
            return maxResultItems;
        }

        public void setMaxResultItems(int maxResultItems) {
            this.maxResultItems = maxResultItems;
        }

        public int getMaxItemChars() {
            return maxItemChars;
        }

        public void setMaxItemChars(int maxItemChars) {
            this.maxItemChars = maxItemChars;
        }
    }

    public static class Executors {
        private final Pool mcpInit = new Pool(2, 8, 128, 60);
        private final Pool mcpExecution = new Pool(4, 16, 128, 60);
        private final Pool builtInExecution = new Pool(4, 16, 128, 60);
        private final Pool mcpReadiness = new Pool(2, 8, 128, 60);
        private final ProgressExecutor progress = new ProgressExecutor();

        public Pool getMcpInit() {
            return mcpInit;
        }

        public Pool getMcpExecution() {
            return mcpExecution;
        }

        public Pool getBuiltInExecution() {
            return builtInExecution;
        }

        public Pool getMcpReadiness() {
            return mcpReadiness;
        }

        public ProgressExecutor getProgress() {
            return progress;
        }
    }

    public static class ProgressExecutor {
        private int poolSize = 2;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }
    }

    public static class Pool {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private int keepAliveSeconds;

        public Pool() {
        }

        public Pool(int corePoolSize, int maxPoolSize, int queueCapacity, int keepAliveSeconds) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }
    }
}
