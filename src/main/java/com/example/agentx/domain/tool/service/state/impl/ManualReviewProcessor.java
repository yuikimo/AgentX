package com.example.agentx.domain.tool.service.state.impl;

import com.example.agentx.domain.tool.constant.ToolStatus;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.domain.tool.service.state.ToolStateProcessor;

/**
 * 人工审核状态处理器
 */
public class ManualReviewProcessor implements ToolStateProcessor {

    @Override
    public ToolStatus getStatus() {
        return ToolStatus.MANUAL_REVIEW;
    }

    @Override
    public ToolStatus getNextStatus() {
        // 人工审核后的下一状态由人工操作决定，自动流程中不会进入下一步
        return null;
    }

    @Override
    public void process(ToolEntity tool) {
        // 人工审核状态只能等待人工审核，不自动处理
    }

}
