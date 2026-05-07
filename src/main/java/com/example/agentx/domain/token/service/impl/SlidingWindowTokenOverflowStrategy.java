package com.example.agentx.domain.token.service.impl;

import org.springframework.stereotype.Service;
import com.example.agentx.domain.token.model.TokenMessage;
import com.example.agentx.domain.token.model.TokenProcessResult;
import com.example.agentx.domain.token.model.config.TokenOverflowConfig;
import com.example.agentx.domain.shared.enums.TokenOverflowStrategyEnum;
import com.example.agentx.domain.token.service.TokenOverflowStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 滑动窗口Token超限处理策略实现 根据Token数量保留最新消息，超出窗口的旧消息将被丢弃 */
@Service
public class SlidingWindowTokenOverflowStrategy implements TokenOverflowStrategy {

    /** 默认最大Token数 */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** 默认预留缓冲比例 */
    private static final double DEFAULT_RESERVE_RATIO = 0.1;

    /** 策略配置 */
    private final TokenOverflowConfig config;

    /** 构造函数
     * 
     * @param config 策略配置 */
    public SlidingWindowTokenOverflowStrategy(TokenOverflowConfig config) {
        this.config = config;
    }

    /** 处理消息列表，应用滑动窗口策略
     * 
     * @param messages 待处理的消息列表
     * @return 处理后保留的消息列表 */
    @Override
    public TokenProcessResult process(List<TokenMessage> messages, TokenOverflowConfig tokenOverflowConfig) {
        TokenOverflowConfig effectiveConfig = tokenOverflowConfig != null ? tokenOverflowConfig : this.config;
        if (!needsProcessing(messages, effectiveConfig)) {
            TokenProcessResult result = new TokenProcessResult();
            result.setRetainedMessages(messages);
            result.setStrategyName(getName());
            result.setProcessed(false);
            result.setTotalTokens(calculateTotalTokens(messages));
            return result;
        }

        // 历史消息通常已按时间升序加载，优先从尾部反向扫描，避免每次 O(n log n) 排序。
        List<TokenMessage> orderedMessages = isAscendingByCreatedAt(messages) ? messages : new ArrayList<>(messages);
        if (orderedMessages != messages) {
            orderedMessages.sort(Comparator.comparing(TokenMessage::getCreatedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        // 计算可用token数（考虑预留空间）
        int maxTokens = getMaxTokens(effectiveConfig);
        double reserveRatio = getReserveRatio(effectiveConfig);
        int reserveTokens = (int) (maxTokens * reserveRatio);
        int availableTokens = maxTokens - reserveTokens;

        // 保留最新的消息，直到达到token限制
        List<TokenMessage> retainedMessages = new ArrayList<>();
        int totalTokens = 0;

        for (int index = orderedMessages.size() - 1; index >= 0; index--) {
            TokenMessage message = orderedMessages.get(index);
            int messageTokens = message.getBodyTokenCount() != null ? message.getBodyTokenCount() : 0;
            if (totalTokens + messageTokens <= availableTokens) {
                retainedMessages.add(message);
                totalTokens += messageTokens;
            } else {
                break;
            }
        }
        Collections.reverse(retainedMessages);

        // 创建结果对象
        TokenProcessResult result = new TokenProcessResult();
        result.setRetainedMessages(retainedMessages);
        result.setStrategyName(getName());
        result.setProcessed(true);
        result.setTotalTokens(totalTokens);

        return result;
    }

    private boolean isAscendingByCreatedAt(List<TokenMessage> messages) {
        if (messages == null || messages.size() < 2) {
            return true;
        }
        for (int index = 1; index < messages.size(); index++) {
            TokenMessage previous = messages.get(index - 1);
            TokenMessage current = messages.get(index);
            if (previous == null || current == null || previous.getCreatedAt() == null || current.getCreatedAt() == null) {
                continue;
            }
            if (previous.getCreatedAt().isAfter(current.getCreatedAt())) {
                return false;
            }
        }
        return true;
    }

    /** 获取策略名称
     * 
     * @return 策略名称 */
    @Override
    public String getName() {
        return TokenOverflowStrategyEnum.SLIDING_WINDOW.name();
    }

    /** 判断是否需要进行Token超限处理
     * 
     * @param messages 待处理的消息列表
     * @return 是否需要处理 */
    @Override
    public boolean needsProcessing(List<TokenMessage> messages) {
        return needsProcessing(messages, this.config);
    }

    private boolean needsProcessing(List<TokenMessage> messages, TokenOverflowConfig overflowConfig) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        int totalTokens = calculateTotalTokens(messages);
        int maxTokens = getMaxTokens(overflowConfig);
        int availableTokens = (int) Math.floor(maxTokens * (1 - getReserveRatio(overflowConfig)));
        return totalTokens > Math.max(1, availableTokens);
    }

    /** 计算消息列表的总token数 */
    private int calculateTotalTokens(List<TokenMessage> messages) {
        return messages.stream().mapToInt(m -> m.getBodyTokenCount() != null ? m.getBodyTokenCount() : 0).sum();
    }

    /** 获取配置的最大Token数，如果未配置则使用默认值
     * 
     * @return 最大Token数 */
    private int getMaxTokens(TokenOverflowConfig overflowConfig) {
        if (overflowConfig == null || overflowConfig.getMaxTokens() == null || overflowConfig.getMaxTokens() <= 0) {
            return DEFAULT_MAX_TOKENS;
        }
        return overflowConfig.getMaxTokens();
    }

    /** 获取配置的预留比例，如果未配置则使用默认值
     * 
     * @return 预留比例 */
    private double getReserveRatio(TokenOverflowConfig overflowConfig) {
        if (overflowConfig == null || overflowConfig.getReserveRatio() == null) {
            return DEFAULT_RESERVE_RATIO;
        }
        return Math.max(0D, Math.min(0.9D, overflowConfig.getReserveRatio()));
    }
}
