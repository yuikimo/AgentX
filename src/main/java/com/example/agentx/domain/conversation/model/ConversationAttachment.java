package com.example.agentx.domain.conversation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.example.agentx.domain.conversation.constant.AttachmentKind;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationAttachment {

    private String url;

    private String name;

    private String contentType;

    private AttachmentKind kind = AttachmentKind.OTHER;

    private String summary;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public AttachmentKind getKind() {
        return kind;
    }

    public void setKind(AttachmentKind kind) {
        this.kind = kind;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public boolean isImage() {
        return kind == AttachmentKind.IMAGE;
    }

    public boolean isDocumentLike() {
        return kind == AttachmentKind.DOCUMENT || kind == AttachmentKind.TEXT;
    }
}
