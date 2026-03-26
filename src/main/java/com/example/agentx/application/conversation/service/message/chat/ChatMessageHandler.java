package com.example.agentx.application.conversation.service.message.chat;

import com.example.agentx.application.conversation.service.message.AbstractMessageHandler;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import org.springframework.stereotype.Component;

/**
 * 标准消息处理器
 */
@Component(value = "chatMessageHandler")
public class ChatMessageHandler extends AbstractMessageHandler {

    protected final HighAvailabilityDomainService highAvailabilityDomainService;
    protected final SessionDomainService sessionDomainService;
    protected final UserSettingsDomainService userSettingsDomainService;
    protected final LLMDomainService llmDomainService;

    public ChatMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
                              HighAvailabilityDomainService highAvailabilityDomainService,
                              SessionDomainService sessionDomainService,
                              UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService) {
        super(llmServiceFactory, messageDomainService, highAvailabilityDomainService, sessionDomainService,
                userSettingsDomainService, llmDomainService);
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.sessionDomainService = sessionDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
    }
}