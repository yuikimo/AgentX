package com.example.agentx.infrastructure.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm.chat")
public class LlmChatProperties {

    private long defaultTimeoutSeconds = 120;
    private long defaultStreamTimeoutSeconds = 120;
    private long attachmentExtraTimeoutSeconds = 60;
    private long attachmentExtraStreamTimeoutSeconds = 120;

    public long getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(long defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public long getDefaultStreamTimeoutSeconds() {
        return defaultStreamTimeoutSeconds;
    }

    public void setDefaultStreamTimeoutSeconds(long defaultStreamTimeoutSeconds) {
        this.defaultStreamTimeoutSeconds = defaultStreamTimeoutSeconds;
    }

    public long getAttachmentExtraTimeoutSeconds() {
        return attachmentExtraTimeoutSeconds;
    }

    public void setAttachmentExtraTimeoutSeconds(long attachmentExtraTimeoutSeconds) {
        this.attachmentExtraTimeoutSeconds = attachmentExtraTimeoutSeconds;
    }

    public long getAttachmentExtraStreamTimeoutSeconds() {
        return attachmentExtraStreamTimeoutSeconds;
    }

    public void setAttachmentExtraStreamTimeoutSeconds(long attachmentExtraStreamTimeoutSeconds) {
        this.attachmentExtraStreamTimeoutSeconds = attachmentExtraStreamTimeoutSeconds;
    }
}
