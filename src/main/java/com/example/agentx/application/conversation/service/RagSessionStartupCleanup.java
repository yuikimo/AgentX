package com.example.agentx.application.conversation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 应用启动时清理遗留的临时RAG会话 */
@Component
public class RagSessionStartupCleanup implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(RagSessionStartupCleanup.class);

    private final RagSessionManager ragSessionManager;

    public RagSessionStartupCleanup(RagSessionManager ragSessionManager) {
        this.ragSessionManager = ragSessionManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("开始执行启动时RAG临时会话清理");
        ragSessionManager.cleanupTemporarySessionsOnStartup();
    }
}
