package com.example.agentx.domain.token.model.config;

import org.springframework.stereotype.Service;
import com.example.agentx.domain.shared.enums.TokenOverflowStrategyEnum;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;

import java.util.concurrent.Executor;

/** Token超限处理配置基础类 */
@Service
public class TokenOverflowConfig {

    /** 策略类型 */
    private TokenOverflowStrategyEnum strategyType;

    /** 最大Token数，适用于滑动窗口和摘要策略 */
    private Integer maxTokens;

    /** 预留缓冲比例，适用于滑动窗口策略 范围0-1之间的小数，表示预留的空间比例 */
    private Double reserveRatio;

    /** 摘要触发阈值（百分比：1-99），适用于摘要策略 */
    private Integer summaryThreshold;

    private ProviderConfig providerConfig;

    /** 摘要模型在请求线程上最多等待的毫秒数；超时后使用本地降级摘要。 */
    private Long summaryGenerationWaitMs;

    /** 摘要模型调用执行器，避免直接占用请求线程。 */
    private Executor summaryExecutor;

    /** 默认构造函数 */
    public TokenOverflowConfig() {
        this.strategyType = TokenOverflowStrategyEnum.NONE;
    }

    /** 带策略类型的构造函数
     * 
     * @param strategyType 策略类型 */
    public TokenOverflowConfig(TokenOverflowStrategyEnum strategyType) {
        this.strategyType = strategyType;
    }

    /** 获取策略类型
     * 
     * @return 策略类型枚举值 */
    public TokenOverflowStrategyEnum getStrategyType() {
        return strategyType;
    }

    /** 设置策略类型
     * 
     * @param strategyType 策略类型 */
    public void setStrategyType(TokenOverflowStrategyEnum strategyType) {
        this.strategyType = strategyType;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getReserveRatio() {
        return reserveRatio;
    }

    public void setReserveRatio(Double reserveRatio) {
        this.reserveRatio = reserveRatio;
    }

    public Integer getSummaryThreshold() {
        return summaryThreshold;
    }

    public void setSummaryThreshold(Integer summaryThreshold) {
        this.summaryThreshold = summaryThreshold;
    }

    /** 创建默认的无策略配置
     * 
     * @return 无策略配置实例 */
    public static TokenOverflowConfig createDefault() {
        return new TokenOverflowConfig(TokenOverflowStrategyEnum.NONE);
    }

    /** 创建滑动窗口策略配置
     * 
     * @param maxTokens 最大Token数
     * @param reserveRatio 预留缓冲比例，默认0.1
     * @return 滑动窗口策略配置实例 */
    public static TokenOverflowConfig createSlidingWindowConfig(int maxTokens, Double reserveRatio) {
        TokenOverflowConfig config = new TokenOverflowConfig(TokenOverflowStrategyEnum.SLIDING_WINDOW);
        config.setMaxTokens(maxTokens);
        config.setReserveRatio(reserveRatio != null ? reserveRatio : 0.1);
        return config;
    }

     /** 创建摘要策略配置
      *
      * @param maxTokens 最大Token数
      * @param summaryThreshold 摘要触发阈值（百分比：1-99），默认35
      * @return 摘要策略配置实例 */
    public static TokenOverflowConfig createSummaryConfig(int maxTokens, Integer summaryThreshold) {
        TokenOverflowConfig config = new TokenOverflowConfig(TokenOverflowStrategyEnum.SUMMARIZE);
        config.setMaxTokens(maxTokens);
        config.setSummaryThreshold(summaryThreshold != null ? summaryThreshold : 35);
        return config;
    }

    public ProviderConfig getProviderConfig() {
        return providerConfig;
    }

    public void setProviderConfig(ProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    public Long getSummaryGenerationWaitMs() {
        return summaryGenerationWaitMs;
    }

    public void setSummaryGenerationWaitMs(Long summaryGenerationWaitMs) {
        this.summaryGenerationWaitMs = summaryGenerationWaitMs;
    }

    public Executor getSummaryExecutor() {
        return summaryExecutor;
    }

    public void setSummaryExecutor(Executor summaryExecutor) {
        this.summaryExecutor = summaryExecutor;
    }
}
