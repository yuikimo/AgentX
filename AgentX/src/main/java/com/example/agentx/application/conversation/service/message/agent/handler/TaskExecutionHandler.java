package com.example.agentx.application.conversation.service.message.agent.handler;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.config.ChatToolProperties;
import com.example.agentx.domain.prompt.ConversationPromptTemplates;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.agent.Agent;
import com.example.agentx.application.conversation.service.message.agent.AgentToolManager;
import com.example.agentx.application.conversation.service.message.agent.ManagedMcpToolProvider;
import com.example.agentx.application.conversation.service.message.agent.event.AgentWorkflowEvent;
import com.example.agentx.application.conversation.service.message.agent.manager.TaskManager;
import com.example.agentx.application.conversation.service.message.agent.workflow.AgentWorkflowContext;
import com.example.agentx.application.conversation.service.message.agent.workflow.AgentWorkflowState;
import com.example.agentx.application.conversation.service.message.CapturedToolExecution;
import com.example.agentx.application.conversation.service.message.ToolCaptureUtils;
import com.example.agentx.application.conversation.service.message.ToolPayloadUtils;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.ContextDomainService;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.prompt.AgentWorkflowPromptTemplates;
import com.example.agentx.domain.prompt.PromptSpec;
import com.example.agentx.domain.task.constant.TaskStatus;
import com.example.agentx.domain.task.model.TaskEntity;
import com.example.agentx.domain.tool.model.UserToolEntity;
import com.example.agentx.domain.tool.service.UserToolDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 任务执行处理器 处理子任务的执行逻辑 */
@Component
public class TaskExecutionHandler extends AbstractAgentHandler {
    private final AgentToolManager toolManager;
    private final UserToolDomainService userToolDomainService;
    private final ChatToolProperties chatToolProperties;

    public TaskExecutionHandler(LLMServiceFactory llmServiceFactory, AgentToolManager toolManager,
            TaskManager taskManager, ContextDomainService contextDomainService, UserToolDomainService userToolDomainService,
            MessageDomainService messageDomainService, ChatToolProperties chatToolProperties) {
        super(llmServiceFactory, taskManager, contextDomainService, messageDomainService);
        this.toolManager = toolManager;
        this.userToolDomainService = userToolDomainService;
        this.chatToolProperties = chatToolProperties;
    }

    @Override
    protected boolean shouldHandle(AgentWorkflowEvent event) {
        return event.getToState() == AgentWorkflowState.TASK_SPLIT_COMPLETED;
    }

    @Override
    protected void transitionToNextState(AgentWorkflowContext<?> context) {
        context.transitionTo(AgentWorkflowState.TASK_EXECUTING);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> void processEvent(AgentWorkflowContext<?> contextObj) {
        AgentWorkflowContext<T> context = (AgentWorkflowContext<T>) contextObj;

        ToolProvider toolProvider = null;
        try {
            // 获取工具提供者
            ChatContext chatContext = contextObj.getChatContext();
            TaskToolRuntimeContext toolRuntimeContext = buildToolRuntimeContext(chatContext, chatContext != null
                    ? chatContext.getUserMessage()
                    : null);
            toolProvider = toolRuntimeContext.toolProvider();

            // 依次执行每个子任务
            while (context.hasNextTask()) {
                String taskName = context.getNextTask();
                if (taskName == null) {
                    break;
                }

                TaskEntity subTask = context.getSubTaskMap().get(taskName);
                executeSubTask(context, subTask, taskName, toolRuntimeContext);

                // 更新父任务进度
                taskManager.updateTaskProgress(context.getParentTask(), context.getCompletedTaskCount(),
                        context.getTotalTaskCount());
            }

            // 所有子任务执行完成，转换到任务执行完成状态
            context.transitionTo(AgentWorkflowState.TASK_EXECUTED);

        } catch (Exception e) {
            context.handleError(e);
        } finally {
            closeToolProvider(toolProvider);
        }
    }

    /** 执行单个子任务 */
    private <T> void executeSubTask(AgentWorkflowContext<T> context, TaskEntity subTask, String taskName,
            TaskToolRuntimeContext toolRuntimeContext) {

        try {
            String taskId = subTask.getId();
            // 更新任务状态为进行中
            taskManager.updateTaskStatus(subTask, TaskStatus.IN_PROGRESS);

            // 保存执行消息
            MessageEntity taskCallMessageEntity = createMessageEntity(context, MessageType.TASK_EXEC, taskName, 0);
            messageDomainService.saveMessage(Collections.singletonList(taskCallMessageEntity));

            // 通知前端当前执行的任务
            context.sendEndMessage(taskName, MessageType.TASK_EXEC);

            // 通知前端任务状态
            context.sendEndWithTaskIdMessage(taskId, MessageType.TASK_STATUS_TO_LOADING);

            // 获取用户原始请求
            String userRequest = context.getChatContext().getUserMessage();

            // 获取之前子任务的结果
            Map<String, String> previousTaskResults = context.getTaskResults();

            // 构建任务提示词
            PromptSpec taskPromptSpec = AgentWorkflowPromptTemplates.buildTaskExecutionPromptSpec(userRequest, taskName,
                    previousTaskResults, chatToolProperties.getMaxCalls(), toolRuntimeContext.toolCatalogPrompt(),
                    toolRuntimeContext.presetToolPrompt(), toolRuntimeContext.toolAvailabilityNotice());

            // 执行子任务
            ChatModel strandClient = llmServiceFactory.getStrandClient(context.getChatContext().getProvider(),
                    context.getChatContext().getModel());

            List<CapturedToolExecution> capturedToolExecutions = Collections.synchronizedList(new ArrayList<>());
            ToolProvider effectiveToolProvider = wrapToolProviderForCapture(toolRuntimeContext.toolProvider(),
                    capturedToolExecutions);

            // 创建Agent服务
            var agentBuilder = AiServices.builder(Agent.class).chatModel(strandClient)
                    .maxSequentialToolsInvocations(Math.max(1, chatToolProperties.getMaxCalls()));
            if (effectiveToolProvider != null) {
                agentBuilder.toolProvider(effectiveToolProvider);
            }
            Agent agent = agentBuilder.build();

            // 执行任务，直接使用完整提示词
            AiMessage aiMessage = agent.chat(taskPromptSpec.getSystemPrompt(), taskPromptSpec.getUserPrompt());

            // 处理工具调用
            if (!capturedToolExecutions.isEmpty()) {
                handleToolCalls(capturedToolExecutions, context);
            } else if (aiMessage.hasToolExecutionRequests()) {
                handlePendingToolCalls(aiMessage, context);
            }

            // 获取任务结果
            String taskResult = aiMessage.text();

            // 保存子任务结果
            context.addTaskResult(taskName, taskResult);
            taskManager.completeTask(subTask, taskResult);

            // 通知前端任务完成
            context.sendEndWithTaskIdMessage(taskId, MessageType.TASK_STATUS_TO_FINISH);

        } catch (Exception e) {
            // 处理子任务执行异常，但不影响其他子任务执行
            subTask.updateStatus(TaskStatus.FAILED);
            subTask.setTaskResult("执行失败: " + e.getMessage());
            taskManager.updateTaskStatus(subTask, TaskStatus.FAILED);

            // 记录错误并继续
            context.sendEndMessage("任务 '" + taskName + "' 执行失败: " + e.getMessage(), MessageType.ERROR);

            // 为了工作流继续，我们仍然增加已完成任务计数
            context.addTaskResult(taskName, "执行失败: " + e.getMessage());
        }
    }

    /** 处理真实已执行的工具调用 */
    private <T> void handleToolCalls(List<CapturedToolExecution> capturedToolExecutions, AgentWorkflowContext<T> context) {
        // 创建工具调用消息实体
        MessageEntity toolCallMessageEntity = createMessageEntity(context, MessageType.TOOL_CALL, null, 0);
        StringBuilder toolCallsContent = new StringBuilder("工具调用:\n");

        capturedToolExecutions.forEach(execution -> {
            String toolName = execution.request() != null ? execution.request().name() : "unknown";
            boolean success = isToolExecutionSuccessful(execution.result());
            toolCallsContent.append("- ").append(toolName).append("（").append(success ? "成功" : "失败").append("）\n");

            // 通知前端工具调用
            context.sendEndMessage("执行工具：" + toolName, MessageType.TOOL_CALL,
                    buildToolPayload(execution.request(), execution.result(), execution.executionTime()));
        });

        // 设置工具调用内容并保存
        toolCallMessageEntity.setContent(toolCallsContent.toString());
        toolCallMessageEntity.setMetadata(buildMultiToolPayload(capturedToolExecutions));
        messageDomainService.saveMessage(Collections.singletonList(toolCallMessageEntity));

        // 更新上下文
        if (context.getChatContext().getContextEntity() != null) {
            if (!context.getChatContext().getContextEntity().hasActiveWindowStartMessageId()
                    && StringUtils.hasText(toolCallMessageEntity.getId())) {
                context.getChatContext().getContextEntity()
                        .setActiveWindowStartMessageId(toolCallMessageEntity.getId());
            }
            context.getChatContext().getContextEntity().setActiveMessages(null);
        }
    }

    /** 处理未捕获结果的待执行工具调用（兜底） */
    private <T> void handlePendingToolCalls(AiMessage aiMessage, AgentWorkflowContext<T> context) {
        MessageEntity toolCallMessageEntity = createMessageEntity(context, MessageType.TOOL_CALL, null, 0);
        StringBuilder toolCallsContent = new StringBuilder("工具调用:\n");

        aiMessage.toolExecutionRequests().forEach(toolExecutionRequest -> {
            String toolName = toolExecutionRequest.name();
            toolCallsContent.append("- ").append(toolName).append("（待执行记录）\n");
            context.sendEndMessage("执行工具：" + toolName, MessageType.TOOL_CALL,
                    buildToolPayload(toolExecutionRequest, null, null));
        });

        toolCallMessageEntity.setContent(toolCallsContent.toString());
        toolCallMessageEntity.setMetadata(buildPendingToolPayload(aiMessage.toolExecutionRequests()));
        messageDomainService.saveMessage(Collections.singletonList(toolCallMessageEntity));
    }

    private TaskToolRuntimeContext buildToolRuntimeContext(ChatContext chatContext, String toolSelectionPrompt) {
        ToolProvider toolProvider = null;
        String toolCatalogPrompt = "";
        String presetToolPrompt = "";
        String toolAvailabilityNotice = "";
        if (chatContext == null || chatContext.getAgent() == null) {
            return new TaskToolRuntimeContext(null, toolCatalogPrompt, presetToolPrompt, toolAvailabilityNotice);
        }
        List<String> toolIds = chatContext.getAgent().getToolIds();
        if (toolIds == null || toolIds.isEmpty()) {
            return new TaskToolRuntimeContext(null, toolCatalogPrompt, presetToolPrompt, toolAvailabilityNotice);
        }
        List<UserToolEntity> installTools = userToolDomainService.getInstallTool(toolIds, chatContext.getUserId());
        List<String> mcpServerNames = installTools.stream().map(UserToolEntity::getMcpServerName)
                .filter(StringUtils::hasText).distinct().collect(Collectors.toList());
        if (mcpServerNames.isEmpty()) {
            return new TaskToolRuntimeContext(null, toolCatalogPrompt, presetToolPrompt, toolAvailabilityNotice);
        }
        AgentToolManager.ToolProviderBuildResult buildResult = toolManager.buildToolProvider(mcpServerNames,
                chatContext.getAgent().getToolPresetParams(), chatContext.getUserId());
        toolProvider = buildResult != null ? buildResult.toolProvider() : null;
        if (buildResult != null && buildResult.unavailableMessages() != null && !buildResult.unavailableMessages().isEmpty()) {
            toolAvailabilityNotice = toolManager.buildToolAvailabilityNotice(buildResult.unavailableMessages());
        }
        presetToolPrompt = ConversationPromptTemplates
                .generatePresetToolPrompt(chatContext.getAgent().getToolPresetParams());
        toolCatalogPrompt = buildToolCatalogPrompt(chatContext, toolProvider, toolSelectionPrompt,
                resolvePresetEnabledToolNames(chatContext.getAgent().getToolPresetParams()));
        return new TaskToolRuntimeContext(toolProvider, toolCatalogPrompt, presetToolPrompt, toolAvailabilityNotice);
    }

    private String buildToolCatalogPrompt(ChatContext chatContext, ToolProvider toolProvider, String toolSelectionPrompt,
            Set<String> presetEnabledToolNames) {
        if (!chatToolProperties.isIncludeCatalogPrompt()) {
            return "";
        }
        if (toolProvider == null) {
            return "";
        }
        Optional<ToolProviderResult> cachedProviderResult = getCachedExternalTools(toolProvider);
        if (cachedProviderResult.isPresent()) {
            ToolProviderResult providerResult = cachedProviderResult.get();
            if (providerResult == null || providerResult.tools() == null || providerResult.tools().isEmpty()) {
                return "";
            }
            List<ConversationPromptTemplates.ToolCatalogItem> items = providerResult.tools().keySet().stream()
                    .filter(tool -> tool != null && StringUtils.hasText(tool.name()))
                    .sorted(Comparator.comparing(ToolSpecification::name, String.CASE_INSENSITIVE_ORDER))
                    .map(tool -> new ConversationPromptTemplates.ToolCatalogItem(tool.name(),
                            StringUtils.hasText(tool.description()) ? tool.description() : "未提供描述",
                            presetEnabledToolNames.contains(tool.name()), "外部"))
                    .toList();
            return ConversationPromptTemplates.generateToolCatalogPrompt(items);
        }
        return "";
    }

    private Optional<ToolProviderResult> getCachedExternalTools(ToolProvider toolProvider) {
        if (toolProvider instanceof ManagedMcpToolProvider managedMcpToolProvider) {
            return managedMcpToolProvider.getCachedToolProviderResult();
        }
        return Optional.empty();
    }

    private Set<String> resolvePresetEnabledToolNames(Map<String, Map<String, Map<String, String>>> toolPresetParams) {
        if (toolPresetParams == null || toolPresetParams.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> toolNames = new LinkedHashSet<>();
        for (Map<String, Map<String, String>> presetGroup : toolPresetParams.values()) {
            if (presetGroup == null || presetGroup.isEmpty()) {
                continue;
            }
            presetGroup.keySet().stream().filter(StringUtils::hasText).map(String::trim).forEach(toolNames::add);
        }
        return toolNames;
    }

    private ToolProvider wrapToolProviderForCapture(ToolProvider delegate,
            List<CapturedToolExecution> capturedToolExecutions) {
        return ToolCaptureUtils.wrapToolProviderForCapture(delegate, capturedToolExecutions);
    }

    private boolean isToolExecutionSuccessful(String result) {
        return ToolPayloadUtils.isToolExecutionSuccessful(result);
    }

    private String classifyToolError(String result) {
        return ToolPayloadUtils.classifyToolError(result);
    }

    private void closeToolProvider(ToolProvider toolProvider) {
        if (toolProvider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String buildToolPayload(ToolExecutionRequest toolExecutionRequest, String result, Integer durationMs) {
        return ToolPayloadUtils.buildSingleToolPayload(
                toolExecutionRequest != null ? toolExecutionRequest.arguments() : null, result, durationMs);
    }

    private String buildMultiToolPayload(List<CapturedToolExecution> toolExecutionRequests) {
        if (toolExecutionRequests == null || toolExecutionRequests.isEmpty()) {
            return null;
        }
        return ToolPayloadUtils.buildMultiToolPayload(ToolCaptureUtils.toPayloadItems(toolExecutionRequests));
    }

    private String buildPendingToolPayload(List<ToolExecutionRequest> toolExecutionRequests) {
        if (toolExecutionRequests == null || toolExecutionRequests.isEmpty()) {
            return null;
        }
        List<ToolPayloadUtils.PendingToolPayloadItem> toolCalls = new ArrayList<>(toolExecutionRequests.size());
        for (ToolExecutionRequest request : toolExecutionRequests) {
            toolCalls.add(new ToolPayloadUtils.PendingToolPayloadItem(request.name(), request.arguments()));
        }
        return ToolPayloadUtils.buildPendingToolPayload(toolCalls);
    }

    private record TaskToolRuntimeContext(ToolProvider toolProvider, String toolCatalogPrompt, String presetToolPrompt,
            String toolAvailabilityNotice) {
    }
}
