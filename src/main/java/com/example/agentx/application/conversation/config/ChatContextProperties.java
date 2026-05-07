package com.example.agentx.application.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对话上下文装配配置 */
@ConfigurationProperties(prefix = "chat.context")
public class ChatContextProperties {

    private final FastPath fastPath = new FastPath();

    private final Summary summary = new Summary();

    private final History history = new History();

    private final MemoryWindow memoryWindow = new MemoryWindow();

    private final Memory memory = new Memory();

    private final Prompt prompt = new Prompt();

    private final Attachment attachment = new Attachment();

    private final FragmentEmitter fragmentEmitter = new FragmentEmitter();

    public FastPath getFastPath() {
        return fastPath;
    }

    public Summary getSummary() {
        return summary;
    }

    public History getHistory() {
        return history;
    }

    public MemoryWindow getMemoryWindow() {
        return memoryWindow;
    }

    public Memory getMemory() {
        return memory;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public Attachment getAttachment() {
        return attachment;
    }

    public FragmentEmitter getFragmentEmitter() {
        return fragmentEmitter;
    }

    public static class Summary {
        private long generationWaitMs = 2500;

        public long getGenerationWaitMs() {
            return generationWaitMs;
        }

        public void setGenerationWaitMs(long generationWaitMs) {
            this.generationWaitMs = generationWaitMs;
        }
    }

    public static class FastPath {
        private int maxHistoryMessages = 8;
        private int maxApproxTokens = 512;

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }

        public int getMaxApproxTokens() {
            return maxApproxTokens;
        }

        public void setMaxApproxTokens(int maxApproxTokens) {
            this.maxApproxTokens = maxApproxTokens;
        }
    }

    public static class History {
        private int maxActiveMessages = 256;
        private int ragWindowSize = 12;
        private int recentMultimodalTurns = 2;
        private int historicalImageReferenceLimit = 3;
        private int historicalImageSummaryLimit = 2;
        private int detailedAttachmentTurns = 3;
        private int annotateRelativeTimeAfterMinutes = 30;

        public int getMaxActiveMessages() {
            return maxActiveMessages;
        }

        public void setMaxActiveMessages(int maxActiveMessages) {
            this.maxActiveMessages = maxActiveMessages;
        }

        public int getRagWindowSize() {
            return ragWindowSize;
        }

        public void setRagWindowSize(int ragWindowSize) {
            this.ragWindowSize = ragWindowSize;
        }

        public int getRecentMultimodalTurns() {
            return recentMultimodalTurns;
        }

        public void setRecentMultimodalTurns(int recentMultimodalTurns) {
            this.recentMultimodalTurns = recentMultimodalTurns;
        }

        public int getHistoricalImageReferenceLimit() {
            return historicalImageReferenceLimit;
        }

        public void setHistoricalImageReferenceLimit(int historicalImageReferenceLimit) {
            this.historicalImageReferenceLimit = historicalImageReferenceLimit;
        }

        public int getHistoricalImageSummaryLimit() {
            return historicalImageSummaryLimit;
        }

        public void setHistoricalImageSummaryLimit(int historicalImageSummaryLimit) {
            this.historicalImageSummaryLimit = historicalImageSummaryLimit;
        }

        public int getDetailedAttachmentTurns() {
            return detailedAttachmentTurns;
        }

        public void setDetailedAttachmentTurns(int detailedAttachmentTurns) {
            this.detailedAttachmentTurns = detailedAttachmentTurns;
        }

        public int getAnnotateRelativeTimeAfterMinutes() {
            return annotateRelativeTimeAfterMinutes;
        }

        public void setAnnotateRelativeTimeAfterMinutes(int annotateRelativeTimeAfterMinutes) {
            this.annotateRelativeTimeAfterMinutes = annotateRelativeTimeAfterMinutes;
        }
    }

    public static class MemoryWindow {
        private int defaultSize = 64;
        private int minSize = 32;
        private int maxSize = 256;
        private int summaryPadding = 8;
        private int maxTokenDivisor = 128;

        public int getDefaultSize() {
            return defaultSize;
        }

        public void setDefaultSize(int defaultSize) {
            this.defaultSize = defaultSize;
        }

        public int getMinSize() {
            return minSize;
        }

        public void setMinSize(int minSize) {
            this.minSize = minSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getSummaryPadding() {
            return summaryPadding;
        }

        public void setSummaryPadding(int summaryPadding) {
            this.summaryPadding = summaryPadding;
        }

        public int getMaxTokenDivisor() {
            return maxTokenDivisor;
        }

        public void setMaxTokenDivisor(int maxTokenDivisor) {
            this.maxTokenDivisor = maxTokenDivisor;
        }
    }

    public static class Memory {
        private int topK = 5;
        private int defaultBudgetTokens = 256;
        private int minBudgetTokens = 120;
        private int maxBudgetTokens = 768;
        private int itemOverheadTokens = 24;
        private int stableBudgetRatio = 45;
        private long recallCacheTtlMs = 30000;
        private int maxStableItems = 3;
        private int maxDynamicItems = 2;
        private double minInjectScore = 0.55;
        private double maxScoreDropFromTop = 0.22;
        private boolean includeCrossSessionDynamicMemories = true;
        private int crossSessionTaskVisibleDays = 14;
        private int crossSessionEpisodicVisibleDays = 2;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getDefaultBudgetTokens() {
            return defaultBudgetTokens;
        }

        public void setDefaultBudgetTokens(int defaultBudgetTokens) {
            this.defaultBudgetTokens = defaultBudgetTokens;
        }

        public int getMinBudgetTokens() {
            return minBudgetTokens;
        }

        public void setMinBudgetTokens(int minBudgetTokens) {
            this.minBudgetTokens = minBudgetTokens;
        }

        public int getMaxBudgetTokens() {
            return maxBudgetTokens;
        }

        public void setMaxBudgetTokens(int maxBudgetTokens) {
            this.maxBudgetTokens = maxBudgetTokens;
        }

        public int getItemOverheadTokens() {
            return itemOverheadTokens;
        }

        public void setItemOverheadTokens(int itemOverheadTokens) {
            this.itemOverheadTokens = itemOverheadTokens;
        }

        public int getStableBudgetRatio() {
            return stableBudgetRatio;
        }

        public void setStableBudgetRatio(int stableBudgetRatio) {
            this.stableBudgetRatio = stableBudgetRatio;
        }

        public long getRecallCacheTtlMs() {
            return recallCacheTtlMs;
        }

        public void setRecallCacheTtlMs(long recallCacheTtlMs) {
            this.recallCacheTtlMs = recallCacheTtlMs;
        }

        public int getMaxStableItems() {
            return maxStableItems;
        }

        public void setMaxStableItems(int maxStableItems) {
            this.maxStableItems = maxStableItems;
        }

        public int getMaxDynamicItems() {
            return maxDynamicItems;
        }

        public void setMaxDynamicItems(int maxDynamicItems) {
            this.maxDynamicItems = maxDynamicItems;
        }

        public double getMinInjectScore() {
            return minInjectScore;
        }

        public void setMinInjectScore(double minInjectScore) {
            this.minInjectScore = minInjectScore;
        }

        public double getMaxScoreDropFromTop() {
            return maxScoreDropFromTop;
        }

        public void setMaxScoreDropFromTop(double maxScoreDropFromTop) {
            this.maxScoreDropFromTop = maxScoreDropFromTop;
        }

        public boolean isIncludeCrossSessionDynamicMemories() {
            return includeCrossSessionDynamicMemories;
        }

        public void setIncludeCrossSessionDynamicMemories(boolean includeCrossSessionDynamicMemories) {
            this.includeCrossSessionDynamicMemories = includeCrossSessionDynamicMemories;
        }

        public int getCrossSessionTaskVisibleDays() {
            return crossSessionTaskVisibleDays;
        }

        public void setCrossSessionTaskVisibleDays(int crossSessionTaskVisibleDays) {
            this.crossSessionTaskVisibleDays = crossSessionTaskVisibleDays;
        }

        public int getCrossSessionEpisodicVisibleDays() {
            return crossSessionEpisodicVisibleDays;
        }

        public void setCrossSessionEpisodicVisibleDays(int crossSessionEpisodicVisibleDays) {
            this.crossSessionEpisodicVisibleDays = crossSessionEpisodicVisibleDays;
        }
    }

    public static class Prompt {
        private int defaultSystemBudgetTokens = 1200;
        private int minSystemBudgetTokens = 320;
        private int maxSystemBudgetTokens = 2600;
        private int smallContextThreshold = 8192;
        private int mediumContextThreshold = 32768;
        private double smallContextSystemBudgetRatio = 0.4;
        private double mediumContextSystemBudgetRatio = 0.3;
        private double largeContextSystemBudgetRatio = 0.2;

        public int getDefaultSystemBudgetTokens() {
            return defaultSystemBudgetTokens;
        }

        public void setDefaultSystemBudgetTokens(int defaultSystemBudgetTokens) {
            this.defaultSystemBudgetTokens = defaultSystemBudgetTokens;
        }

        public int getMinSystemBudgetTokens() {
            return minSystemBudgetTokens;
        }

        public void setMinSystemBudgetTokens(int minSystemBudgetTokens) {
            this.minSystemBudgetTokens = minSystemBudgetTokens;
        }

        public int getMaxSystemBudgetTokens() {
            return maxSystemBudgetTokens;
        }

        public void setMaxSystemBudgetTokens(int maxSystemBudgetTokens) {
            this.maxSystemBudgetTokens = maxSystemBudgetTokens;
        }

        public int getSmallContextThreshold() {
            return smallContextThreshold;
        }

        public void setSmallContextThreshold(int smallContextThreshold) {
            this.smallContextThreshold = smallContextThreshold;
        }

        public int getMediumContextThreshold() {
            return mediumContextThreshold;
        }

        public void setMediumContextThreshold(int mediumContextThreshold) {
            this.mediumContextThreshold = mediumContextThreshold;
        }

        public double getSmallContextSystemBudgetRatio() {
            return smallContextSystemBudgetRatio;
        }

        public void setSmallContextSystemBudgetRatio(double smallContextSystemBudgetRatio) {
            this.smallContextSystemBudgetRatio = smallContextSystemBudgetRatio;
        }

        public double getMediumContextSystemBudgetRatio() {
            return mediumContextSystemBudgetRatio;
        }

        public void setMediumContextSystemBudgetRatio(double mediumContextSystemBudgetRatio) {
            this.mediumContextSystemBudgetRatio = mediumContextSystemBudgetRatio;
        }

        public double getLargeContextSystemBudgetRatio() {
            return largeContextSystemBudgetRatio;
        }

        public void setLargeContextSystemBudgetRatio(double largeContextSystemBudgetRatio) {
            this.largeContextSystemBudgetRatio = largeContextSystemBudgetRatio;
        }
    }

    public static class Attachment {
        private double currentTurnBudgetRatio = 0.2;
        private int defaultBudgetTokens = 320;
        private int minBudgetTokens = 120;
        private int maxBudgetTokens = 900;
        private long syncFullMaxBytes = 262144;
        private long syncSampledMaxBytes = 3145728;
        private long imageFallbackOcrWaitMs = 2500;
        private long imagePreferredOcrWaitMs = 1200;
        private long summaryCacheTtlMs = 86400000;
        private long summaryCacheMaxSize = 10000;
        private int documentSummaryBudgetTokens = 2000;
        private final ProcessingExecutor processingExecutor = new ProcessingExecutor();

        public double getCurrentTurnBudgetRatio() {
            return currentTurnBudgetRatio;
        }

        public void setCurrentTurnBudgetRatio(double currentTurnBudgetRatio) {
            this.currentTurnBudgetRatio = currentTurnBudgetRatio;
        }

        public int getDefaultBudgetTokens() {
            return defaultBudgetTokens;
        }

        public void setDefaultBudgetTokens(int defaultBudgetTokens) {
            this.defaultBudgetTokens = defaultBudgetTokens;
        }

        public int getMinBudgetTokens() {
            return minBudgetTokens;
        }

        public void setMinBudgetTokens(int minBudgetTokens) {
            this.minBudgetTokens = minBudgetTokens;
        }

        public int getMaxBudgetTokens() {
            return maxBudgetTokens;
        }

        public void setMaxBudgetTokens(int maxBudgetTokens) {
            this.maxBudgetTokens = maxBudgetTokens;
        }

        public long getSyncFullMaxBytes() {
            return syncFullMaxBytes;
        }

        public void setSyncFullMaxBytes(long syncFullMaxBytes) {
            this.syncFullMaxBytes = syncFullMaxBytes;
        }

        public long getSyncSampledMaxBytes() {
            return syncSampledMaxBytes;
        }

        public void setSyncSampledMaxBytes(long syncSampledMaxBytes) {
            this.syncSampledMaxBytes = syncSampledMaxBytes;
        }

        public long getImageFallbackOcrWaitMs() {
            return imageFallbackOcrWaitMs;
        }

        public void setImageFallbackOcrWaitMs(long imageFallbackOcrWaitMs) {
            this.imageFallbackOcrWaitMs = imageFallbackOcrWaitMs;
        }

        public long getImagePreferredOcrWaitMs() {
            return imagePreferredOcrWaitMs;
        }

        public void setImagePreferredOcrWaitMs(long imagePreferredOcrWaitMs) {
            this.imagePreferredOcrWaitMs = imagePreferredOcrWaitMs;
        }

        public long getSummaryCacheTtlMs() {
            return summaryCacheTtlMs;
        }

        public void setSummaryCacheTtlMs(long summaryCacheTtlMs) {
            this.summaryCacheTtlMs = summaryCacheTtlMs;
        }

        public long getSummaryCacheMaxSize() {
            return summaryCacheMaxSize;
        }

        public void setSummaryCacheMaxSize(long summaryCacheMaxSize) {
            this.summaryCacheMaxSize = summaryCacheMaxSize;
        }

        public int getDocumentSummaryBudgetTokens() {
            return documentSummaryBudgetTokens;
        }

        public void setDocumentSummaryBudgetTokens(int documentSummaryBudgetTokens) {
            this.documentSummaryBudgetTokens = documentSummaryBudgetTokens;
        }

        public ProcessingExecutor getProcessingExecutor() {
            return processingExecutor;
        }
    }

    public static class ProcessingExecutor {
        private int corePoolSize = 2;
        private int maxPoolSize = 8;
        private int queueCapacity = 200;
        private int keepAliveSeconds = 60;

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

    public static class FragmentEmitter {
        private final RagAnswer ragAnswer = new RagAnswer();

        public RagAnswer getRagAnswer() {
            return ragAnswer;
        }
    }

    public static class RagAnswer {
        private int flushChars = 160;
        private long flushIntervalMs = 48;
        private int maxDeferChars = 640;

        public int getFlushChars() {
            return flushChars;
        }

        public void setFlushChars(int flushChars) {
            this.flushChars = flushChars;
        }

        public long getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
        }

        public int getMaxDeferChars() {
            return maxDeferChars;
        }

        public void setMaxDeferChars(int maxDeferChars) {
            this.maxDeferChars = maxDeferChars;
        }
    }
}
