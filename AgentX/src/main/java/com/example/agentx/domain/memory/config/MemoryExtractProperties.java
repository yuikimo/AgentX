package com.example.agentx.domain.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 记忆抽取配置 */
@ConfigurationProperties(prefix = "memory.extract")
public class MemoryExtractProperties {

    private int batchTurnThreshold = 3;

    private long idleFlushDelayMillis = 60_000L;

    private int batchMaxTokens = 1600;

    private int schedulerPoolSize = 2;

    private int pendingMaxBatches = 10000;

    private long pendingBatchTtlMillis = 300_000L;

    private int shortMessageMinCodePoints = 6;

    private float minImportance = 0.8f;

    private float episodicMinImportance = 0.9f;

    private float pendingMinImportance = 0.5f;

    private int pendingPromoteSeenCount = 2;

    private int maxMemoriesPerBatch = 3;

    private int minMemoryTextCodePoints = 6;

    private int maxTagsPerMemory = 6;

    private int maxUserMessageChars = 2000;

    private int maxAssistantReplyChars = 2500;

    private int maxRecentHistoryChars = 480;

    private long extractionTimeoutMillis = 15_000L;

    private int regexFallbackMaxChars = 10_000;

    private boolean promptCachingEnabled = true;

    private final Filter filter = new Filter();

    public int getBatchTurnThreshold() {
        return batchTurnThreshold;
    }

    public void setBatchTurnThreshold(int batchTurnThreshold) {
        this.batchTurnThreshold = batchTurnThreshold;
    }

    public long getIdleFlushDelayMillis() {
        return idleFlushDelayMillis;
    }

    public void setIdleFlushDelayMillis(long idleFlushDelayMillis) {
        this.idleFlushDelayMillis = idleFlushDelayMillis;
    }

    public int getBatchMaxTokens() {
        return batchMaxTokens;
    }

    public void setBatchMaxTokens(int batchMaxTokens) {
        this.batchMaxTokens = batchMaxTokens;
    }

    public int getSchedulerPoolSize() {
        return schedulerPoolSize;
    }

    public void setSchedulerPoolSize(int schedulerPoolSize) {
        this.schedulerPoolSize = schedulerPoolSize;
    }

    public int getPendingMaxBatches() {
        return pendingMaxBatches;
    }

    public void setPendingMaxBatches(int pendingMaxBatches) {
        this.pendingMaxBatches = pendingMaxBatches;
    }

    public long getPendingBatchTtlMillis() {
        return pendingBatchTtlMillis;
    }

    public void setPendingBatchTtlMillis(long pendingBatchTtlMillis) {
        this.pendingBatchTtlMillis = pendingBatchTtlMillis;
    }

    public int getShortMessageMinCodePoints() {
        return shortMessageMinCodePoints;
    }

    public void setShortMessageMinCodePoints(int shortMessageMinCodePoints) {
        this.shortMessageMinCodePoints = shortMessageMinCodePoints;
    }

    public float getMinImportance() {
        return minImportance;
    }

    public void setMinImportance(float minImportance) {
        this.minImportance = minImportance;
    }

    public float getEpisodicMinImportance() {
        return episodicMinImportance;
    }

    public void setEpisodicMinImportance(float episodicMinImportance) {
        this.episodicMinImportance = episodicMinImportance;
    }

    public float getPendingMinImportance() {
        return pendingMinImportance;
    }

    public void setPendingMinImportance(float pendingMinImportance) {
        this.pendingMinImportance = pendingMinImportance;
    }

    public int getPendingPromoteSeenCount() {
        return pendingPromoteSeenCount;
    }

    public void setPendingPromoteSeenCount(int pendingPromoteSeenCount) {
        this.pendingPromoteSeenCount = pendingPromoteSeenCount;
    }

    public int getMaxMemoriesPerBatch() {
        return maxMemoriesPerBatch;
    }

    public void setMaxMemoriesPerBatch(int maxMemoriesPerBatch) {
        this.maxMemoriesPerBatch = maxMemoriesPerBatch;
    }

    public int getMinMemoryTextCodePoints() {
        return minMemoryTextCodePoints;
    }

    public void setMinMemoryTextCodePoints(int minMemoryTextCodePoints) {
        this.minMemoryTextCodePoints = minMemoryTextCodePoints;
    }

    public int getMaxTagsPerMemory() {
        return maxTagsPerMemory;
    }

    public void setMaxTagsPerMemory(int maxTagsPerMemory) {
        this.maxTagsPerMemory = maxTagsPerMemory;
    }

    public int getMaxUserMessageChars() {
        return maxUserMessageChars;
    }

    public void setMaxUserMessageChars(int maxUserMessageChars) {
        this.maxUserMessageChars = maxUserMessageChars;
    }

    public int getMaxAssistantReplyChars() {
        return maxAssistantReplyChars;
    }

    public void setMaxAssistantReplyChars(int maxAssistantReplyChars) {
        this.maxAssistantReplyChars = maxAssistantReplyChars;
    }

    public int getMaxRecentHistoryChars() {
        return maxRecentHistoryChars;
    }

    public void setMaxRecentHistoryChars(int maxRecentHistoryChars) {
        this.maxRecentHistoryChars = maxRecentHistoryChars;
    }

    public long getExtractionTimeoutMillis() {
        return extractionTimeoutMillis;
    }

    public void setExtractionTimeoutMillis(long extractionTimeoutMillis) {
        this.extractionTimeoutMillis = extractionTimeoutMillis;
    }

    public int getRegexFallbackMaxChars() {
        return regexFallbackMaxChars;
    }

    public void setRegexFallbackMaxChars(int regexFallbackMaxChars) {
        this.regexFallbackMaxChars = regexFallbackMaxChars;
    }

    public boolean isPromptCachingEnabled() {
        return promptCachingEnabled;
    }

    public void setPromptCachingEnabled(boolean promptCachingEnabled) {
        this.promptCachingEnabled = promptCachingEnabled;
    }

    public Filter getFilter() {
        return filter;
    }

    public static class Filter {

        private List<String> ackMessages = new ArrayList<>(List.of("好", "好的", "嗯", "嗯嗯", "收到", "谢谢", "感谢", "ok",
                "okay", "thanks", "thank you", "了解", "明白", "行", "可以", "继续", "yes", "no"));

        private List<String> commandPrefixes = new ArrayList<>(List.of("ls", "cat", "cd", "pwd", "open", "查看", "列出",
                "打开", "运行", "执行", "搜索", "检索", "下载", "上传", "浏览", "read", "show"));

        private List<String> memorySignalKeywords = new ArrayList<>(
                List.of("以后", "偏好", "喜欢", "习惯", "一直", "长期", "计划", "目标", "打算", "我用", "我是", "我在", "常用",
                        "主要用", "改成", "改为", "记住", "请用", "两周", "下周", "本周", "prefer", "preference",
                        "usually", "always", "goal", "plan", "background", "profile"));

        private List<String> immediateFlushSignalKeywords = new ArrayList<>(
                List.of("记住", "以后", "默认", "请一直", "统一用", "固定用", "偏好", "习惯", "长期", "我的风格", "我的格式",
                        "请按这个风格", "从现在开始", "后续都", "always", "remember", "from now on", "default",
                        "preference", "prefer"));

        public List<String> getAckMessages() {
            return ackMessages;
        }

        public void setAckMessages(List<String> ackMessages) {
            this.ackMessages = ackMessages;
        }

        public List<String> getCommandPrefixes() {
            return commandPrefixes;
        }

        public void setCommandPrefixes(List<String> commandPrefixes) {
            this.commandPrefixes = commandPrefixes;
        }

        public List<String> getMemorySignalKeywords() {
            return memorySignalKeywords;
        }

        public void setMemorySignalKeywords(List<String> memorySignalKeywords) {
            this.memorySignalKeywords = memorySignalKeywords;
        }

        public List<String> getImmediateFlushSignalKeywords() {
            return immediateFlushSignalKeywords;
        }

        public void setImmediateFlushSignalKeywords(List<String> immediateFlushSignalKeywords) {
            this.immediateFlushSignalKeywords = immediateFlushSignalKeywords;
        }
    }
}
