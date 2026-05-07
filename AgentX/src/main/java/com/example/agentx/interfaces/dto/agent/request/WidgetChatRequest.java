package com.example.agentx.interfaces.dto.agent.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.example.agentx.domain.conversation.model.ConversationAttachment;

import java.util.List;
import java.util.stream.Collectors;

/** 小组件聊天请求 */
public class WidgetChatRequest {

    /** 用户消息 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息内容长度不能超过4000字符")
    private String message;

    /** 匿名会话ID */
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    /** 文件URL列表 */
    private List<String> fileUrls;

    private List<ConversationAttachment> attachments;

    // Getter和Setter方法
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
        return fileUrls != null ? fileUrls : List.of();
    }

    public void setFileUrls(List<String> fileUrls) {
        this.fileUrls = fileUrls;
    }

    public List<ConversationAttachment> getAttachments() {
        return attachments != null ? attachments : List.of();
    }

    public void setAttachments(List<ConversationAttachment> attachments) {
        this.attachments = attachments;
    }
}
