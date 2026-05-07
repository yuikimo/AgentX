package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/** Captured tool execution data shared by chat and workflow tool runtimes. */
public record CapturedToolExecution(ToolExecutionRequest request, String result, Integer executionTime) {
}
