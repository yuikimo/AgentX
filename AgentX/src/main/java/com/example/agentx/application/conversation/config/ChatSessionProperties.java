package com.example.agentx.application.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.session")
public class ChatSessionProperties {

    private long maxActiveMs = 300000;
    private long cleanupFixedDelayMs = 60000;
    private long cleanupInitialDelayMs = 60000;

    public long getMaxActiveMs() {
        return maxActiveMs;
    }

    public void setMaxActiveMs(long maxActiveMs) {
        this.maxActiveMs = maxActiveMs;
    }

    public long getCleanupFixedDelayMs() {
        return cleanupFixedDelayMs;
    }

    public void setCleanupFixedDelayMs(long cleanupFixedDelayMs) {
        this.cleanupFixedDelayMs = cleanupFixedDelayMs;
    }

    public long getCleanupInitialDelayMs() {
        return cleanupInitialDelayMs;
    }

    public void setCleanupInitialDelayMs(long cleanupInitialDelayMs) {
        this.cleanupInitialDelayMs = cleanupInitialDelayMs;
    }
}
