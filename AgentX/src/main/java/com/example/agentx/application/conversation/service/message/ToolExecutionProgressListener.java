package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/** 流式工具执行进度监听器。 */
public interface ToolExecutionProgressListener {

    void onStarted(ToolExecutionRequest request);

    void onProgress(ToolExecutionRequest request, long elapsedMs);
}
