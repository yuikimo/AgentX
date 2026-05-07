package com.example.agentx.domain.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 记忆召回与语义去重配置 */
@ConfigurationProperties(prefix = "memory.recall")
public class MemoryRecallProperties {

    private long timeoutMs = 1200;

    private double minScore = 0.3;

    private double highConfidenceScore = 0.72;

    private int candidateMultiplier = 3;

    private double similarityWeight = 0.55;

    private double importanceWeight = 0.20;

    private double recencyWeight = 0.10;

    private double typePriorWeight = 0.05;

    private double frequencyWeight = 0.10;

    private double intentSimilarityBoost = 0.10;

    private double intentImportanceBoost = 0.10;

    private double intentRecencyBoost = 0.08;

    private double intentTypePriorBoost = 0.05;

    private double mmrLambda = 0.78;

    private double profileTimeDecayDays = 365;

    private double taskTimeDecayDays = 45;

    private double factTimeDecayDays = 180;

    private double episodicTimeDecayDays = 14;

    private double distributionMeanThreshold = 0.68;

    private double distributionStddevThreshold = 0.12;

    private double distributionTopGapThreshold = 0.10;

    private List<String> queryStopWords = new ArrayList<>(
            List.of("请", "请问", "帮我", "帮忙", "一下", "一下子", "一下吧", "吗", "呢", "吧", "呀", "啊", "the",
                    "a", "an", "please", "can", "could", "you"));

    private List<String> profileKeywords = new ArrayList<>(List.of("偏好", "喜欢", "习惯", "格式", "风格", "以后", "回答",
            "用什么语言", "preference", "prefer", "format", "style", "usually", "always"));

    private List<String> taskKeywords = new ArrayList<>(List.of("计划", "目标", "任务", "待办", "要做", "todo", "plan",
            "goal", "task", "roadmap"));

    private List<String> factKeywords = new ArrayList<>(List.of("我是", "我在", "身份", "背景", "公司", "职业", "哪里",
            "什么技术栈", "background", "profile", "company", "role", "based"));

    private List<String> episodicKeywords = new ArrayList<>(List.of("刚才", "刚刚", "之前", "上次", "前面", "这轮", "recent",
            "previous", "last time", "earlier"));

    private final SemanticDedupe semanticDedupe = new SemanticDedupe();

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public double getHighConfidenceScore() {
        return highConfidenceScore;
    }

    public void setHighConfidenceScore(double highConfidenceScore) {
        this.highConfidenceScore = highConfidenceScore;
    }

    public int getCandidateMultiplier() {
        return candidateMultiplier;
    }

    public void setCandidateMultiplier(int candidateMultiplier) {
        this.candidateMultiplier = candidateMultiplier;
    }

    public double getSimilarityWeight() {
        return similarityWeight;
    }

    public void setSimilarityWeight(double similarityWeight) {
        this.similarityWeight = similarityWeight;
    }

    public double getImportanceWeight() {
        return importanceWeight;
    }

    public void setImportanceWeight(double importanceWeight) {
        this.importanceWeight = importanceWeight;
    }

    public double getRecencyWeight() {
        return recencyWeight;
    }

    public void setRecencyWeight(double recencyWeight) {
        this.recencyWeight = recencyWeight;
    }

    public double getTypePriorWeight() {
        return typePriorWeight;
    }

    public void setTypePriorWeight(double typePriorWeight) {
        this.typePriorWeight = typePriorWeight;
    }

    public double getFrequencyWeight() {
        return frequencyWeight;
    }

    public void setFrequencyWeight(double frequencyWeight) {
        this.frequencyWeight = frequencyWeight;
    }

    public double getIntentSimilarityBoost() {
        return intentSimilarityBoost;
    }

    public void setIntentSimilarityBoost(double intentSimilarityBoost) {
        this.intentSimilarityBoost = intentSimilarityBoost;
    }

    public double getIntentImportanceBoost() {
        return intentImportanceBoost;
    }

    public void setIntentImportanceBoost(double intentImportanceBoost) {
        this.intentImportanceBoost = intentImportanceBoost;
    }

    public double getIntentRecencyBoost() {
        return intentRecencyBoost;
    }

    public void setIntentRecencyBoost(double intentRecencyBoost) {
        this.intentRecencyBoost = intentRecencyBoost;
    }

    public double getIntentTypePriorBoost() {
        return intentTypePriorBoost;
    }

    public void setIntentTypePriorBoost(double intentTypePriorBoost) {
        this.intentTypePriorBoost = intentTypePriorBoost;
    }

    public double getMmrLambda() {
        return mmrLambda;
    }

    public void setMmrLambda(double mmrLambda) {
        this.mmrLambda = mmrLambda;
    }

    public double getProfileTimeDecayDays() {
        return profileTimeDecayDays;
    }

    public void setProfileTimeDecayDays(double profileTimeDecayDays) {
        this.profileTimeDecayDays = profileTimeDecayDays;
    }

    public double getTaskTimeDecayDays() {
        return taskTimeDecayDays;
    }

    public void setTaskTimeDecayDays(double taskTimeDecayDays) {
        this.taskTimeDecayDays = taskTimeDecayDays;
    }

    public double getFactTimeDecayDays() {
        return factTimeDecayDays;
    }

    public void setFactTimeDecayDays(double factTimeDecayDays) {
        this.factTimeDecayDays = factTimeDecayDays;
    }

    public double getEpisodicTimeDecayDays() {
        return episodicTimeDecayDays;
    }

    public void setEpisodicTimeDecayDays(double episodicTimeDecayDays) {
        this.episodicTimeDecayDays = episodicTimeDecayDays;
    }

    public double getDistributionMeanThreshold() {
        return distributionMeanThreshold;
    }

    public void setDistributionMeanThreshold(double distributionMeanThreshold) {
        this.distributionMeanThreshold = distributionMeanThreshold;
    }

    public double getDistributionStddevThreshold() {
        return distributionStddevThreshold;
    }

    public void setDistributionStddevThreshold(double distributionStddevThreshold) {
        this.distributionStddevThreshold = distributionStddevThreshold;
    }

    public double getDistributionTopGapThreshold() {
        return distributionTopGapThreshold;
    }

    public void setDistributionTopGapThreshold(double distributionTopGapThreshold) {
        this.distributionTopGapThreshold = distributionTopGapThreshold;
    }

    public List<String> getQueryStopWords() {
        return queryStopWords;
    }

    public void setQueryStopWords(List<String> queryStopWords) {
        this.queryStopWords = queryStopWords;
    }

    public List<String> getProfileKeywords() {
        return profileKeywords;
    }

    public void setProfileKeywords(List<String> profileKeywords) {
        this.profileKeywords = profileKeywords;
    }

    public List<String> getTaskKeywords() {
        return taskKeywords;
    }

    public void setTaskKeywords(List<String> taskKeywords) {
        this.taskKeywords = taskKeywords;
    }

    public List<String> getFactKeywords() {
        return factKeywords;
    }

    public void setFactKeywords(List<String> factKeywords) {
        this.factKeywords = factKeywords;
    }

    public List<String> getEpisodicKeywords() {
        return episodicKeywords;
    }

    public void setEpisodicKeywords(List<String> episodicKeywords) {
        this.episodicKeywords = episodicKeywords;
    }

    public SemanticDedupe getSemanticDedupe() {
        return semanticDedupe;
    }

    public static class SemanticDedupe {

        private int maxResults = 6;

        private double minScore = 0.90;

        private double intraBatchTextSimilarity = 0.82;

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = maxResults;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public double getIntraBatchTextSimilarity() {
            return intraBatchTextSimilarity;
        }

        public void setIntraBatchTextSimilarity(double intraBatchTextSimilarity) {
            this.intraBatchTextSimilarity = intraBatchTextSimilarity;
        }
    }
}
