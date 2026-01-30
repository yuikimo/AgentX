package com.example.agentx.domain.conversation.handler;

import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.ContextDomainService;
import com.example.agentx.domain.conversation.service.ConversationDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.infrastructure.transport.MessageTransport;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Agent消息处理器
 * 用于支持工具调用的对话模式
 * 实现任务拆分、执行和结果汇总的流程
 */
@Component(value = "agentMessageHandler")
public class AgentMessageHandler extends ChatMessageHandler {

    private final TaskDomainService taskDomainService;

    // 任务拆分提示词 测试过程用的
    String decompositionTestPrompt =
            "你是一个专业的任务规划专家，请根据用户的需求，将复杂任务分解为合理的子任务序列,只拆分为3条子任务。请分解为合理的子任务序列，直接以数字编号的形式列出，无需额外解释";

    // 任务拆分提示词
    String decompositionPrompt =
            "你是一个专业的任务规划专家，请根据用户的需求，将复杂任务分解为合理的子任务序列。" +
                    "在分解任务时，请考虑以下几点：" +
                    "\n1. 充分理解用户的真实需求和背景，挖掘潜在的子任务" +
                    "\n2. 子任务应该覆盖问题解决的整个过程，确保完整性" +
                    "\n3. 根据任务的复杂度，决定合适的子任务粒度和数量" +
                    "\n4. 子任务应按照合理的顺序排列，确保执行的流畅性" +
                    "\n5. 子任务描述应面向用户，清晰易懂，避免技术术语" +
                    "\n6. 创造性地考虑用户可能忽略的方面，提供全面的规划" +
                    "\n\n以下是用户的需求：" +
                    "\n\n请分解为合理的子任务序列，直接以数字编号的形式列出，无需额外解释。";

    // 任务结果汇总提示词
    private final String summaryPrompt = "你是一个高效的任务结果整合专家。我已经完成了以下子任务，请根据这些子任务的结果，提供一个全面、连贯且条理清晰的总结。" +
            "总结应该：" +
            "\n1. 融合所有子任务的关键信息" +
            "\n2. 消除重复内容" +
            "\n3. 使用简洁明了的语言" +
            "\n4. 保持专业性和准确性" +
            "\n5. 按逻辑顺序组织内容" +
            "\n\n以下是各子任务及其结果：\n%s" +
            "\n\n请提供一个全面的总结:";

    // Agent接口定义
    public interface Agent {
        AiMessage chat(String prompt);
    }

    public AgentMessageHandler(
            ConversationDomainService conversationDomainService,
            ContextDomainService contextDomainService,
            LLMServiceFactory llmServiceFactory,
            TaskDomainService taskDomainService) {
        super(conversationDomainService, contextDomainService, llmServiceFactory);
        this.taskDomainService = taskDomainService;
    }

    private List<String> getTools() {
        return new ArrayList<>();
    }

    @Override
    public <T> T handleChat(ChatEnvironment environment, MessageTransport<T> messageTransport) {

    }

    /**
     * 将任务文本拆分为子任务列表
     */
    private List<String> splitTask(String task) {
        return Arrays.stream(task.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 获取任务描述并流式响应
     */
    private <T> void getTaskDesc(
            StreamingChatLanguageModel llmClient,
            ChatRequest llmRequest,
            T connection,
            MessageTransport<T> transport,
            ChatEnvironment environment,
            MessageEntity userMessageEntity,
            MessageEntity llmMessageEntity,
            TaskEntity parentTask,
            Map<String, TaskEntity> subTaskMap,
            List<String> tasks,
            CompletableFuture<Boolean> splitTaskFuture) {

    }

    /**
     * 汇总子任务结果并流式响应
     */
    private <T> void summarizeResults(
            StreamingChatLanguageModel llmClient,
            ChatEnvironment environment,
            String taskResults,
            T connection,
            MessageTransport<T> transport,
            MessageEntity summaryMessageEntity,
            TaskEntity parentTask) {
        ChatRequest.Builder requestBuilder = new ChatRequest.Builder();
        ArrayList<ChatMessage> messages = new ArrayList<>();

        // 添加系统提示词
        messages.add(new SystemMessage(String.format(summaryPrompt, taskResults)));

        // 添加用户消息
        messages.add(new UserMessage("请基于上述子任务结果提供总结"));

        // 构建请求参数
        OpenAiChatRequestParameters.Builder parameters = new OpenAiChatRequestParameters.Builder();
        parameters.modelName(environment.getModel().getModelId())
                .topP(environment.getLlmModelConfig().getTopP())
                .temperature(environment.getLlmModelConfig().getTemperature());
        requestBuilder.messages(messages);
        requestBuilder.parameters(parameters.build());

        // 流式响应汇总结果
        llmClient.doChat(requestBuilder.build(), new StreamingChatResponseHandler() {
            StringBuilder fullSummary = new StringBuilder();

            @Override
            public void onPartialResponse(String partialResponse) {
                fullSummary.append(partialResponse);

                // 发送流式响应给前端 - 确保明确使用TEXT类型，不与任务列表混合
                AgentChatResponse response = AgentChatResponse.build(partialResponse, false, MessageType.TEXT);
                transport.sendMessage(connection, response);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                // 设置LLM消息内容和token数
                TokenUsage tokenUsage = completeResponse.metadata().tokenUsage();
                Integer outputTokenCount = tokenUsage.outputTokenCount();

                String summary = completeResponse.aiMessage().text();
                summaryMessageEntity.setContent(summary);
                summaryMessageEntity.setTokenCount(outputTokenCount);
                summaryMessageEntity.setMessageType(MessageType.TEXT); // 确保消息类型为TEXT

                // 更新父任务为完成状态
                parentTask.updateStatus(TaskStatus.COMPLETED);
                parentTask.setTaskResult(summary);
                taskDomainService.updateTask(parentTask);

                // 然后再发送最终完成标记 - 独立消息
                AgentChatResponse finishResponse = AgentChatResponse.build("", true, MessageType.TEXT);
                transport.sendMessage(connection, finishResponse);
                transport.completeConnection(connection);

                // 更新上下文
                environment.getContextEntity().getActiveMessages().add(summaryMessageEntity.getId());
                contextDomainService.insertOrUpdate(environment.getContextEntity());
                conversationDomainService.saveMessage(summaryMessageEntity);
            }

            @Override
            public void onError(Throwable error) {
                transport.handleError(connection, error);
            }
        });
    }

    /**
     * 准备任务拆分请求
     */
    protected ChatRequest prepareSplitTaskRequest(ChatEnvironment environment) {
        // 构建聊天消息列表
        List<ChatMessage> chatMessages = new ArrayList<ChatMessage>;
        ChatRequest.Builder chatRequestBuilder = new ChatRequest.Builder();

        // 添加任务拆分系统提示词
        String prompt = String.format(decompositionTestPrompt, environment.getUserMessage());
        chatMessages.add(new SystemMessage(prompt));

        // 添加当前用户消息
        chatMessages.add(new UserMessage(environment.getUserMessage()));

        // 构建请求参数
        OpenAiChatRequestParameters.Builder parameters = new OpenAiChatRequestParameters.Builder();
        parameters.modelName(environment.getModel().getModelId())
                .topP(environment.getLlmModelConfig().getTopP())
                .temperature(environment.getLlmModelConfig().getTemperature());

        // 设置消息和参数
        chatRequestBuilder.messages(chatMessages);
        chatRequestBuilder.parameters(parameters.build());

        return chatRequestBuilder.build();
    }


    /**
     * 准备子任务请求
     */
    protected ChatRequest prepareTaskRequest(ChatEnvironment environment, String taskWithContext) {
        // 构建聊天消息列表
        List<ChatMessage> chatMessages = new ArrayList<>();
        ChatRequest.Builder chatRequestBuilder = new ChatRequest.Builder();

        // 添加系统提示词
        chatMessages.add(new SystemMessage("你是一个高效的任务执行助手，请基于给定的上下文和当前任务，完成当前任务。如果需要使用前面子任务的结果，请充分利用。"));

        // 添加当前任务描述
        chatMessages.add(new UserMessage(taskWithContext));

        // 构建请求参数
        OpenAiChatRequestParameters.Builder parameters = new OpenAiChatRequestParameters.Builder();
        parameters
                .modelName(environment.getModel().getModelId())
                .topP(environment.getLlmModelConfig().getTopP())
                .temperature(environment.getLlmModelConfig().getTemperature());
        // 设置消息和参数
        chatRequestBuilder.messages(chatMessages);
        chatRequestBuilder.parameters(parameters.build());
        return chatRequestBuilder.build();
    }
}
