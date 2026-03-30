package com.example.agentx.application.conversation.service.message.agent;

import com.example.agentx.application.conversation.service.ChatSessionManager;
import com.example.agentx.application.conversation.service.message.builtin.BuiltInToolRegistry;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agentx.application.conversation.service.handler.context.ChatContext;
import com.example.agentx.application.conversation.service.message.TracingMessageHandler;
import com.example.agentx.application.conversation.service.message.agent.tool.RagToolManager;
import com.example.agentx.application.trace.collector.TraceCollector;
import com.example.agentx.domain.conversation.service.MessageDomainService;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.application.billing.service.BillingService;
import com.example.agentx.domain.user.service.AccountDomainService;

/**
 * Agent消息处理器 用于支持工具调用的对话模式 实现任务拆分、执行和结果汇总的工作流 使用事件驱动架构进行状态转换
 */
@Component(value = "agentMessageHandler")
public class AgentMessageHandler extends TracingMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentMessageHandler.class);
    private AgentToolManager agentToolManager;

    public AgentMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
                               HighAvailabilityDomainService highAvailabilityDomainService,
                               SessionDomainService sessionDomainService,
                               UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
                               BuiltInToolRegistry builtInToolRegistry, BillingService billingService,
                               AccountDomainService accountDomainService, ChatSessionManager chatSessionManager,
                               TraceCollector traceCollector, AgentToolManager agentToolManager) {
        super(llmServiceFactory, messageDomainService, highAvailabilityDomainService, sessionDomainService,
                userSettingsDomainService, llmDomainService, builtInToolRegistry, billingService, accountDomainService,
                chatSessionManager, traceCollector);
        this.agentToolManager = agentToolManager;
    }

    @Override
    protected ToolProvider provideTools(ChatContext chatContext) {
        // 关键改造：传递用户ID给工具管理器
        return agentToolManager.createToolProvider(agentToolManager.getAvailableTools(chatContext),
                chatContext.getAgent().getToolPresetParams(), chatContext.getUserId() // 新增：传递用户ID
        );
    }
}