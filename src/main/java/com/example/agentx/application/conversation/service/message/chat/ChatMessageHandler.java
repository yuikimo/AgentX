package com.example.agentx.application.conversation.service.message.chat;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.application.conversation.service.message.AbstractMessageHandler;
import com.example.agentx.application.conversation.service.message.builtin.BuiltInToolRegistry;
import com.example.agentx.application.conversation.service.ChatSessionManager;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.application.billing.service.BillingService;
import com.example.agentx.domain.user.service.AccountDomainService;

/**
 * 标准消息处理器
 */
@Component(value = "chatMessageHandler")
public class ChatMessageHandler extends AbstractMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatMessageHandler.class);

    public ChatMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
                              HighAvailabilityDomainService highAvailabilityDomainService,
                              SessionDomainService sessionDomainService,
                              UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
                              BuiltInToolRegistry builtInToolRegistry, BillingService billingService,
                              AccountDomainService accountDomainService, ChatSessionManager chatSessionManager) {
        super(llmServiceFactory, messageDomainService, highAvailabilityDomainService, sessionDomainService,
                userSettingsDomainService, llmDomainService, builtInToolRegistry, billingService, accountDomainService,
                chatSessionManager);
    }
}