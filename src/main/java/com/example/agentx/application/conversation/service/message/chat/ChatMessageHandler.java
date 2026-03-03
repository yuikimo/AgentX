package com.example.agentx.application.conversation.service.message.chat;

import com.example.agentx.application.conversation.service.message.AbstractMessageHandler;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import org.springframework.stereotype.Component;

/**
 * 标准消息处理器
 */
@Component(value = "chatMessageHandler")
public class ChatMessageHandler extends AbstractMessageHandler {

    public ChatMessageHandler(
            LLMServiceFactory llmServiceFactory,
            MessageDomainService messageDomainService) {
        super(llmServiceFactory, messageDomainService);
    }
}