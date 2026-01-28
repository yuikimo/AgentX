package com.example.agentx.domain.token.service;

import com.example.agentx.domain.token.model.TokenMessage;
import com.example.agentx.domain.token.model.TokenProcessResult;

import java.util.List;

/**
 * Token溢出处理策略接口
 */
public interface TokenOverflowStrategy {
    /**
     * 处理消息列表
     *
     * @param messages 待处理的消息列表
     * @return 处理结果，包含处理后的消息列表、摘要等信息
     */
    TokenProcessResult process(List<TokenMessage> messages);

    /**
     * 获取策略名称
     *
     * @return 策略名称
     */
    String getName();

    /**
     * 检查是否需要处理
     *
     * @param messages 待检查的消息列表
     * @return 是否需要处理
     */
    boolean needsProcessing(List<TokenMessage> messages);

    /**
     * 计算消息列表的总token数
     */
    default int calculateTotalTokens(List<TokenMessage> messages) {
        return messages.stream()
                .mapToInt(message -> {
                    Integer tokenCount = message.getTokenCount();
                    return tokenCount != null ? tokenCount : 0;
                })
                .sum();
    }
}
