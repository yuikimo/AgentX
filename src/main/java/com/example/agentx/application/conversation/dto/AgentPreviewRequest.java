package com.example.agentx.application.conversation.dto;

import com.example.agentx.domain.conversation.model.ConversationAttachment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Agent预览请求DTO 用于预览尚未创建的Agent的对话效果 */
public class AgentPreviewRequest {

    /** 用户当前输入的消息 */
    private String userMessage;

    /** 系统提示词 */
    private String systemPrompt;

    /** 工具ID列表 */
    private List<String> toolIds;

    /** 工具预设参数 */
    private Map<String, Map<String, Map<String, String>>> toolPresetParams;

    /** 历史消息上下文 */
    private List<MessageDTO> messageHistory;

    /** 使用的模型ID，如果为空则使用用户默认模型 */
    private String modelId;

    /** 文件列表 */
    private List<String> fileUrls = new ArrayList<>();

    /** 附件详情 */
    private List<ConversationAttachment> attachments = new ArrayList<>();

    /** 知识库ID列表，用于RAG功能 */
    private List<String> knowledgeBaseIds = new ArrayList<>();

    /** 是否支持附件/多模态 */
    private Boolean multiModal;

    public List<String> getFileUrls() {
        if ((fileUrls == null || fileUrls.isEmpty()) && attachments != null && !attachments.isEmpty()) {
            return attachments.stream().map(ConversationAttachment::getUrl).filter(url -> url != null && !url.isBlank())
                    .collect(Collectors.toList());
        }
        return fileUrls;
    }

    public void setFileUrls(List<String> fileUrls) {
        this.fileUrls = fileUrls;
    }

    public List<ConversationAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<ConversationAttachment> attachments) {
        this.attachments = attachments;
    }

    public List<String> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds;
    }

    public Boolean getMultiModal() {
        return multiModal;
    }

    public void setMultiModal(Boolean multiModal) {
        this.multiModal = multiModal;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<String> getToolIds() {
        return toolIds;
    }

    public void setToolIds(List<String> toolIds) {
        this.toolIds = toolIds;
    }

    public Map<String, Map<String, Map<String, String>>> getToolPresetParams() {
        return toolPresetParams;
    }

    public void setToolPresetParams(Map<String, Map<String, Map<String, String>>> toolPresetParams) {
        this.toolPresetParams = toolPresetParams;
    }

    public List<MessageDTO> getMessageHistory() {
        return messageHistory;
    }

    public void setMessageHistory(List<MessageDTO> messageHistory) {
        this.messageHistory = messageHistory;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
}
