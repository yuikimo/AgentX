package com.example.agentx.domain.agent.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.prompt.AgentMetaPromptTemplates;
import com.example.agentx.domain.prompt.PromptSpec;
import com.example.agentx.domain.tool.model.ToolEntity;
import com.example.agentx.infrastructure.exception.BusinessException;

import java.util.Arrays;
import java.util.List;

/** 系统提示词生成领域服务 */
@Service
public class SystemPromptGeneratorDomainService {

    /** 生成系统提示词 该方法只负责核心的提示词生成逻辑，不涉及其他领域的调用 */
    public String generateSystemPrompt(String agentName, String agentDescription, List<ToolEntity> tools,
            ChatModel chatModel) {

        // 1. 构建生成prompt
        PromptSpec promptSpec = AgentMetaPromptTemplates
                .buildGenerationPromptSpec(buildOverviewPrompt(agentName, agentDescription, tools));

        // 2. 调用LLM生成（LLM客户端由应用层传入）
        SystemMessage systemMessage = new SystemMessage(promptSpec.getSystemPrompt());
        UserMessage userMessage = new UserMessage(promptSpec.getUserPrompt());

        ChatResponse response = chatModel.chat(Arrays.asList(systemMessage, userMessage));

        // 3. 后处理和验证
        String generatedPrompt = response.aiMessage().text();
        return validateAndCleanPrompt(generatedPrompt);
    }

    /** 构建用于生成的提示词 */
    private String buildOverviewPrompt(String agentName, String agentDescription, List<ToolEntity> tools) {
        StringBuilder overview = new StringBuilder();
        overview.append("名称: ").append(agentName).append("\n");
        overview.append("描述: ").append(agentDescription).append("\n\n");
        overview.append("可用工具概览:\n");

        if (tools != null && !tools.isEmpty()) {
            for (ToolEntity tool : tools) {
                overview.append("- 工具名称: ").append(tool.getName()).append("\n");
                overview.append("  工具描述: ").append(tool.getDescription()).append("\n");
            }
        } else {
            overview.append("该助手当前没有配置任何特定工具。\n");
        }

        return overview.toString();
    }

    /** 验证和清理生成的提示词 */
    private String validateAndCleanPrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new BusinessException("生成的系统提示词为空");
        }

        // 移除可能的格式标记
        String cleaned = prompt.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "");
        }

        // 基本长度验证
        if (cleaned.length() < 10) {
            throw new BusinessException("生成的系统提示词过短");
        }

        if (cleaned.length() > 5000) {
            throw new BusinessException("生成的系统提示词过长");
        }

        return cleaned.trim();
    }
}
