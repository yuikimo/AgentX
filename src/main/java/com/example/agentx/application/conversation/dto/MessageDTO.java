package com.example.agentx.application.conversation.dto;

import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.conversation.constant.Role;
import com.example.agentx.domain.conversation.model.ConversationAttachment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 消息DTO，用于API响应 */
public class MessageDTO {
    /** 消息ID */
    private String id;
    /** 消息角色 */
    private Role role;
    /** 消息内容 */
    private String content;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 提供商 */
    private String provider;
    /** 模型 */
    private String model;

    /** 消息类型 */
    private MessageType messageType;

    /** 附加负载，工具调用等场景使用 */
    private String payload;

    private List<String> fileUrls = new ArrayList<>();

    private List<ConversationAttachment> attachments = new ArrayList<>();
    /** 无参构造函数 */
    public MessageDTO() {
    }

    // Getter和Setter方法
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

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
}
