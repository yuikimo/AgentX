package com.example.agentx.domain.rag.dto;

import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;
import com.example.agentx.domain.rag.model.ModelConfig;

import java.util.List;

/** 混合检索配置对象 封装混合检索的所有参数，提高方法可读性和扩展性
 * 
 * @author claude */
public class HybridSearchConfig {

    public static final int DEFAULT_MAX_RESULTS = 15;
    public static final int ABSOLUTE_MAX_RESULTS = 100;

    /** 数据集ID列表 */
    private List<String> dataSetIds;

    /** 查询问题 */
    private String question;

    /** 最大返回结果数量 */
    private Integer maxResults;

    /** 最小相似度阈值 */
    private Double minScore;

    /** 低置信度回退阈值，null表示关闭回退 */
    private Double fallbackMinScore;

    /** 是否启用重排序 */
    private Boolean enableRerank;

    /** 候选结果倍数 */
    private Integer candidateMultiplier;

    /** 嵌入模型配置 */
    private EmbeddingModelFactory.EmbeddingConfig embeddingConfig;

    /** 向量表名（按Embedding Profile路由） */
    private String vectorTableName;

    /** 向量维度 */
    private Integer vectorDimension;

    /** 是否启用查询扩展 */
    private Boolean enableQueryExpansion;

    /** 混合检索总超时时间（秒） */
    private Integer timeoutSeconds;

    /** 聊天模型配置（用于HyDE） */
    private ModelConfig chatModelConfig;

    /** 预先生成的HyDE查询计划（跨group复用） */
    private com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan hydeQueryPlan;

    public HybridSearchConfig() {
    }

    public HybridSearchConfig(List<String> dataSetIds, String question) {
        this.dataSetIds = dataSetIds;
        this.question = question;
        // 设置默认值
        this.maxResults = DEFAULT_MAX_RESULTS;
        this.minScore = 0.7;
        this.fallbackMinScore = 0.3;
        this.enableRerank = true;
        this.candidateMultiplier = 2;
        this.enableQueryExpansion = false;
        this.timeoutSeconds = 30;
    }

    /** 构造器模式创建配置 */
    public static class Builder {
        private HybridSearchConfig config;

        public Builder(List<String> dataSetIds, String question) {
            config = new HybridSearchConfig(dataSetIds, question);
        }

        public Builder maxResults(Integer maxResults) {
            config.setMaxResults(maxResults);
            return this;
        }

        public Builder minScore(Double minScore) {
            config.setMinScore(minScore);
            return this;
        }

        public Builder fallbackMinScore(Double fallbackMinScore) {
            config.setFallbackMinScore(fallbackMinScore);
            return this;
        }

        public Builder enableRerank(Boolean enableRerank) {
            config.setEnableRerank(enableRerank);
            return this;
        }

        public Builder candidateMultiplier(Integer candidateMultiplier) {
            config.setCandidateMultiplier(candidateMultiplier);
            return this;
        }

        public Builder embeddingConfig(EmbeddingModelFactory.EmbeddingConfig embeddingConfig) {
            config.setEmbeddingConfig(embeddingConfig);
            return this;
        }

        public Builder vectorTableName(String vectorTableName) {
            config.setVectorTableName(vectorTableName);
            return this;
        }

        public Builder vectorDimension(Integer vectorDimension) {
            config.setVectorDimension(vectorDimension);
            return this;
        }

        public Builder enableQueryExpansion(Boolean enableQueryExpansion) {
            config.setEnableQueryExpansion(enableQueryExpansion);
            return this;
        }

        public Builder timeoutSeconds(Integer timeoutSeconds) {
            config.setTimeoutSeconds(timeoutSeconds);
            return this;
        }

        public Builder chatModelConfig(ModelConfig chatModelConfig) {
            config.setChatModelConfig(chatModelConfig);
            return this;
        }

        public Builder hydeQueryPlan(com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan hydeQueryPlan) {
            config.setHydeQueryPlan(hydeQueryPlan);
            return this;
        }

        public HybridSearchConfig build() {
            return config;
        }
    }

    /** 静态工厂方法创建构造器 */
    public static Builder builder(List<String> dataSetIds, String question) {
        return new Builder(dataSetIds, question);
    }

    /** 参数验证
     * @return 验证是否通过 */
    public boolean isValid() {
        return dataSetIds != null && !dataSetIds.isEmpty() && question != null && !question.trim().isEmpty()
                && embeddingConfig != null;
    }

    /** 获取验证错误信息
     * @return 错误信息，无错误返回null */
    public String getValidationError() {
        if (dataSetIds == null || dataSetIds.isEmpty()) {
            return "数据集ID列表不能为空";
        }
        if (question == null || question.trim().isEmpty()) {
            return "查询问题不能为空";
        }
        if (embeddingConfig == null) {
            return "嵌入模型配置不能为空";
        }
        return null;
    }

    // Getters and Setters
    public List<String> getDataSetIds() {
        return dataSetIds;
    }

    public void setDataSetIds(List<String> dataSetIds) {
        this.dataSetIds = dataSetIds;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    public Double getFallbackMinScore() {
        return fallbackMinScore;
    }

    public void setFallbackMinScore(Double fallbackMinScore) {
        this.fallbackMinScore = fallbackMinScore;
    }

    public Boolean getEnableRerank() {
        return enableRerank;
    }

    public void setEnableRerank(Boolean enableRerank) {
        this.enableRerank = enableRerank;
    }

    public Integer getCandidateMultiplier() {
        return candidateMultiplier;
    }

    public void setCandidateMultiplier(Integer candidateMultiplier) {
        this.candidateMultiplier = candidateMultiplier;
    }

    public EmbeddingModelFactory.EmbeddingConfig getEmbeddingConfig() {
        return embeddingConfig;
    }

    public void setEmbeddingConfig(EmbeddingModelFactory.EmbeddingConfig embeddingConfig) {
        this.embeddingConfig = embeddingConfig;
    }

    public String getVectorTableName() {
        return vectorTableName;
    }

    public void setVectorTableName(String vectorTableName) {
        this.vectorTableName = vectorTableName;
    }

    public Integer getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(Integer vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    public Boolean getEnableQueryExpansion() {
        return enableQueryExpansion;
    }

    public void setEnableQueryExpansion(Boolean enableQueryExpansion) {
        this.enableQueryExpansion = enableQueryExpansion;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public ModelConfig getChatModelConfig() {
        return chatModelConfig;
    }

    public void setChatModelConfig(ModelConfig chatModelConfig) {
        this.chatModelConfig = chatModelConfig;
    }

    /** 检查是否有有效的聊天模型配置用于HyDE
     * @return 是否可以使用HyDE功能 */
    public boolean hasValidChatModelConfig() {
        return chatModelConfig != null;
    }

    public com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan getHydeQueryPlan() {
        return hydeQueryPlan;
    }

    public void setHydeQueryPlan(com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan hydeQueryPlan) {
        this.hydeQueryPlan = hydeQueryPlan;
    }

    @Override
    public String toString() {
        return "HybridSearchConfig{" + "dataSetIds=" + dataSetIds + ", question='" + question + '\'' + ", maxResults="
                + maxResults + ", minScore=" + minScore + ", fallbackMinScore=" + fallbackMinScore
                + ", enableRerank=" + enableRerank + ", candidateMultiplier="
                + candidateMultiplier + ", enableQueryExpansion=" + enableQueryExpansion + ", timeoutSeconds="
                + timeoutSeconds + ", hasChatModelConfig="
                + hasValidChatModelConfig() + '}';
    }
}
