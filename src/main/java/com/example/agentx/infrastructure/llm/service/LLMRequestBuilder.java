package com.example.agentx.infrastructure.llm.service;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.ContextEntity;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.ContextProcessor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM请求构建器
 * 负责构建发送给LLM的请求
 */
@Component
public class LLMRequestBuilder {

    /**
     * 构建LLM请求
     *
     * @param contextResult 上下文处理结果
     * @param userMessage   用户消息
     * @param systemPrompt  系统提示语
     * @param modelId       模型ID
     * @param temperature   温度参数
     * @param topP          topP参数
     * @return LLM请求对象
     */
    public LLMRequest buildRequest(
            ContextProcessor.ContextResult contextResult,
            String userMessage,
            String systemPrompt,
            String modelId,
            float temperature,
            float topP) {
        // 构建聊天消息列表
        List<ChatMessage> chatMessages = new ArrayList<>();
        ChatRequest.Builder chatRequestBuilder = new ChatRequest.Builder();

        List<Content> userContents = new ArrayList<>();
        List<Content> systemContent = new ArrayList<>();

        // 处理历史消息
        for (MessageEntity messageEntity : contextResult.messageEntities()) {
            Role role = messageEntity.getRole();
            String content = messageEntity.getContent();
            if (Role.USER.equals(role)) {
                userContents.add(new TextContent(content));
            } else if (Role.SYSTEM.equals(role)) {
                systemContent.add(new TextContent(content));
            }
        }

        // 添加摘要信息
        ContextEntity contextEntity = contextResult.contextEntity();
        if (StringUtils.isNotEmpty(contextEntity.getSummary())) {
            String preStr = "以下消息是用户之前的历史消息精炼成的摘要消息";
            chatMessages.add(new AiMessage(preStr + contextEntity.getSummary()));
        }

        // 添加当前用户消息
        userContents.add(new TextContent(userMessage));
        chatMessages.add(new UserMessage(userContents));

        // 添加系统提示词
        if (StringUtils.isNotEmpty(systemPrompt)) {
            chatMessages.add(new SystemMessage(systemPrompt));
        }

        // 设置请求参数
        OpenAiChatRequestParameters.Builder parameters = new OpenAiChatRequestParameters.Builder();
        parameters.modelName(modelId);

        // 转换为Double类型
        Double tempDouble = Double.valueOf(temperature);
        Double topPDouble = Double.valueOf(topP);

        parameters.temperature(tempDouble)
                .topP(topPDouble);

        chatRequestBuilder.messages(chatMessages);
        chatRequestBuilder.parameters(parameters.build());

        // 封装为LLMRequest接口
        return new OpenAILLMRequest(chatRequestBuilder.build());
    }
}
