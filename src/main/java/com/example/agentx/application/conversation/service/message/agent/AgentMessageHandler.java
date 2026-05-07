package com.example.agentx.application.conversation.service.message.agent;

import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.application.conversation.config.ChatContextProperties;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.TracingMessageHandler;
import com.example.agentx.application.conversation.service.message.builtin.BuiltInToolRegistry;
import com.example.agentx.application.conversation.service.ChatSessionManager;
import com.example.agentx.application.trace.collector.TraceCollector;
import com.example.agentx.domain.conversation.model.MessageEntity;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.application.billing.service.BillingService;
import com.example.agentx.domain.user.service.AccountDomainService;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;

/** Agent消息处理器 用于支持工具调用的对话模式 实现任务拆分、执行和结果汇总的工作流 使用事件驱动架构进行状态转换 */
@Component(value = "agentMessageHandler")
public class AgentMessageHandler extends TracingMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentMessageHandler.class);
    private AgentToolManager agentToolManager;

    public AgentMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService, SessionDomainService sessionDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            BuiltInToolRegistry builtInToolRegistry, BillingService billingService,
            AccountDomainService accountDomainService, ChatSessionManager chatSessionManager,
            TraceCollector traceCollector, AgentToolManager agentToolManager, ProviderConfigFactory providerConfigFactory,
            ChatContextProperties chatContextProperties) {
        super(llmServiceFactory, messageDomainService, highAvailabilityDomainService, sessionDomainService,
                userSettingsDomainService, llmDomainService, builtInToolRegistry, billingService, accountDomainService,
                chatSessionManager, traceCollector, providerConfigFactory, chatContextProperties);
        this.agentToolManager = agentToolManager;
    }

    @Override
    protected void onUserMessageProcessed(ChatContext chatContext, MessageEntity userMessage) {
        super.onUserMessageProcessed(chatContext, userMessage);
        if (chatContext == null) {
            return;
        }
        agentToolManager.prewarmToolServers(agentToolManager.getAvailableTools(chatContext), chatContext.getUserId());
    }

    @Override
    protected ToolProvider provideTools(ChatContext chatContext) {
        AgentToolManager.ToolProviderBuildResult result = agentToolManager.buildToolProvider(
                agentToolManager.getAvailableTools(chatContext), chatContext.getAgent().getToolPresetParams(),
                chatContext.getUserId());
        if (chatContext != null && result != null && result.unavailableMessages() != null
                && !result.unavailableMessages().isEmpty()) {
            chatContext.setToolAvailabilityNotice(agentToolManager.buildToolAvailabilityNotice(result.unavailableMessages()));
        }
        return result != null ? result.toolProvider() : null;
    }
}
