package com.example.agentx.application.conversation.service.message;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.agent.ManagedMcpToolProvider;
import com.example.agentx.application.conversation.service.message.builtin.BuiltInToolRegistry;
import com.example.agentx.domain.agent.model.AgentEntity;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class ToolExecutionSupport {
    private final BuiltInToolRegistry builtInToolRegistry;
    private final TaskScheduler toolExecutionProgressTaskScheduler;
    private final ChatToolProperties chatToolProperties;

    public ToolExecutionSupport(BuiltInToolRegistry builtInToolRegistry,
            @Qualifier("toolExecutionProgressTaskScheduler") TaskScheduler toolExecutionProgressTaskScheduler,
            ChatToolProperties chatToolProperties) {
        this.builtInToolRegistry = builtInToolRegistry;
        this.toolExecutionProgressTaskScheduler = toolExecutionProgressTaskScheduler;
        this.chatToolProperties = chatToolProperties;
    }

    public List<CapturedToolExecution> newCaptureBuffer() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    public Map<ToolSpecification, ToolExecutor> prepareBuiltInTools(AgentEntity agent, AtomicInteger toolCallCounter,
            List<CapturedToolExecution> capturedToolExecutions, ToolExecutionProgressListener progressListener) {
        Map<ToolSpecification, ToolExecutor> builtInTools = wrapBuiltInToolExecutors(
                builtInToolRegistry.createToolsForAgent(agent), toolCallCounter);
        if ((capturedToolExecutions == null || capturedToolExecutions.isEmpty()) && progressListener == null
                && builtInTools.isEmpty()) {
            return builtInTools;
        }
        return ToolCaptureUtils.wrapToolExecutorsForCapture(builtInTools, capturedToolExecutions, progressListener,
                toolExecutionProgressTaskScheduler, chatToolProperties.getProgress().getTickIntervalMs());
    }

    public ToolProvider prepareExternalToolProvider(ToolProvider toolProvider, Set<String> builtInToolNames,
            AtomicInteger toolCallCounter, List<CapturedToolExecution> capturedToolExecutions,
            ToolExecutionProgressListener progressListener) {
        ToolProvider effectiveToolProvider = toolProvider;
        if (effectiveToolProvider instanceof ManagedMcpToolProvider managedMcpToolProvider) {
            managedMcpToolProvider.excludeToolNames(builtInToolNames);
            managedMcpToolProvider.setSharedToolCallCounter(toolCallCounter);
        }
        if (capturedToolExecutions != null || progressListener != null) {
            effectiveToolProvider = ToolCaptureUtils.wrapToolProviderForCapture(effectiveToolProvider,
                    capturedToolExecutions, progressListener, toolExecutionProgressTaskScheduler,
                    chatToolProperties.getProgress().getTickIntervalMs());
        }
        return effectiveToolProvider;
    }

    public ToolingBundle prepareTooling(AgentEntity agent, ToolProvider toolProvider,
            List<CapturedToolExecution> capturedToolExecutions) {
        return prepareTooling(agent, toolProvider, capturedToolExecutions, null);
    }

    public ToolingBundle prepareTooling(AgentEntity agent, ToolProvider toolProvider,
            List<CapturedToolExecution> capturedToolExecutions, ToolExecutionProgressListener progressListener) {
        AtomicInteger builtInToolCallCounter = chatToolProperties.isShareToolCallCounter()
                ? resolveSharedToolCallCounter(toolProvider)
                : new AtomicInteger(0);
        Map<ToolSpecification, ToolExecutor> builtInTools = prepareBuiltInTools(agent, builtInToolCallCounter,
                capturedToolExecutions, progressListener);
        Set<String> builtInToolNames = builtInTools.keySet().stream().map(ToolSpecification::name)
                .collect(Collectors.toSet());
        AtomicInteger externalToolCallCounter = chatToolProperties.isShareToolCallCounter()
                ? builtInToolCallCounter
                : new AtomicInteger(0);
        ToolProvider effectiveToolProvider = prepareExternalToolProvider(toolProvider, builtInToolNames,
                externalToolCallCounter, capturedToolExecutions, progressListener);
        return new ToolingBundle(builtInTools, effectiveToolProvider);
    }

    public List<MessageEntity> buildToolMessagesFromExecutions(ChatContext chatContext,
            List<CapturedToolExecution> capturedToolExecutions, Supplier<MessageEntity> messageFactory,
            Consumer<MessageEntity> tokenEstimator) {
        if (capturedToolExecutions == null || capturedToolExecutions.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageEntity> toolMessages = new ArrayList<>(capturedToolExecutions.size());
        for (CapturedToolExecution execution : capturedToolExecutions) {
            String toolName = execution.request() != null ? execution.request().name() : "unknown";
            MessageEntity toolMessage = messageFactory.get();
            toolMessage.setMessageType(MessageType.TOOL_CALL);
            toolMessage.setContent("执行工具：" + toolName);
            toolMessage.setMetadata(ToolPayloadUtils.buildSingleToolPayload(
                    execution.request() != null ? execution.request().arguments() : null, execution.result(),
                    execution.executionTime()));
            tokenEstimator.accept(toolMessage);
            toolMessages.add(toolMessage);
        }
        return toolMessages;
    }

    public CapturedToolExecution findAndRemoveMatchingExecution(List<CapturedToolExecution> capturedToolExecutions,
            ToolExecution toolExecution) {
        if (capturedToolExecutions == null || toolExecution == null) {
            return null;
        }
        return ToolCaptureUtils.findAndRemoveMatchingExecution(capturedToolExecutions, toolExecution.request(),
                toolExecution.result()).orElse(null);
    }

    private AtomicInteger resolveSharedToolCallCounter(ToolProvider toolProvider) {
        if (toolProvider instanceof ManagedMcpToolProvider managedMcpToolProvider) {
            return managedMcpToolProvider.getSharedToolCallCounter();
        }
        return new AtomicInteger(0);
    }

    private Map<ToolSpecification, ToolExecutor> wrapBuiltInToolExecutors(Map<ToolSpecification, ToolExecutor> builtInTools,
            AtomicInteger toolCallCounter) {
        if (builtInTools == null || builtInTools.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ToolSpecification, ToolExecutor> wrappedTools = new LinkedHashMap<>();
        builtInTools.forEach((toolSpecification, toolExecutor) -> wrappedTools.put(toolSpecification,
                (request, memoryId) -> {
                    int currentCount = toolCallCounter.incrementAndGet();
                    if (currentCount > chatToolProperties.getMaxCalls()) {
                        return "❌ 本轮工具调用次数已达到上限，请停止继续调用工具并直接给出结论。";
                    }
                    return toolExecutor.execute(request, memoryId);
                }));
        return wrappedTools;
    }

    public record ToolingBundle(Map<ToolSpecification, ToolExecutor> builtInTools, ToolProvider toolProvider) {
    }
}
