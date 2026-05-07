package com.example.agentx.application.conversation.service.handler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.dto.ChatRequest;
import com.example.agentx.application.conversation.dto.RagChatRequest;
import com.example.agentx.application.conversation.service.message.AbstractMessageHandler;
import com.example.agentx.domain.agent.model.AgentWidgetEntity;

/** 消息处理器工厂 根据智能体类型选择适合的消息处理器 */
@Component
public class MessageHandlerFactory {

    private final AbstractMessageHandler agentMessageHandler;
    private final AbstractMessageHandler ragMessageHandler;

    public MessageHandlerFactory(@Qualifier("agentMessageHandler") AbstractMessageHandler agentMessageHandler,
            @Qualifier("ragMessageHandler") AbstractMessageHandler ragMessageHandler) {
        this.agentMessageHandler = agentMessageHandler;
        this.ragMessageHandler = ragMessageHandler;
    }

    /** 根据请求类型获取合适的消息处理器
     * 
     * @param request 聊天请求
     * @return 消息处理器 */
    public AbstractMessageHandler getHandler(ChatRequest request) {
        if (request instanceof RagChatRequest) {
            return ragMessageHandler;
        }

        // 默认使用标准Agent消息处理器
        return agentMessageHandler;
    }

    /** 根据智能体和Widget配置获取合适的消息处理器 支持根据Widget类型选择不同的处理器
     * 
     * @param widget Widget配置实体（可为null）
     * @return 消息处理器 */
    public AbstractMessageHandler getHandler(AgentWidgetEntity widget) {
        // 如果是RAG类型的Widget，直接使用RagMessageHandler
        if (widget != null && widget.isRagWidget()) {
            return ragMessageHandler;
        }

        // 其他情况使用标准的Agent消息处理器
        return agentMessageHandler;
    }
}
