package com.example.agentx.interfaces.dto.agent.request;

import java.util.List;

/** 系统提示词生成请求 */
public class SystemPromptGenerateRequest {

    /** 可选：Agent ID（用于优先读取工作区绑定模型） */
    private String agentId;

    /** 可选：会话ID（用于优先读取会话绑定的工作区模型） */
    private String sessionId;

    /** 可选：指定模型ID（最高优先级） */
    private String modelId;

    /** Agent名称 */
    private String agentName;

    /** Agent描述 */
    private String agentDescription;

    /** 工具ID列表 */
    private List<String> toolIds;

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentDescription() {
        return agentDescription;
    }

    public void setAgentDescription(String agentDescription) {
        this.agentDescription = agentDescription;
    }

    public List<String> getToolIds() {
        return toolIds;
    }

    public void setToolIds(List<String> toolIds) {
        this.toolIds = toolIds;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
}
