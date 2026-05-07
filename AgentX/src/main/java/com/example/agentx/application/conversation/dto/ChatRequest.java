package com.example.agentx.application.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import com.example.agentx.domain.conversation.model.ConversationAttachment;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 聊天请求DTO */
public class ChatRequest {

    /** 消息内容 */
    @NotBlank(message = "消息内容不可为空")
    private String message;

    /** 会话ID */
    @NotBlank(message = "会话id不可为空")
    private String sessionId;

    private List<String> fileUrls = new ArrayList<>();

    private List<ConversationAttachment> attachments = new ArrayList<>();

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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
