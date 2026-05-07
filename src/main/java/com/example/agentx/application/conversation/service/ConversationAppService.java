package com.example.agentx.application.conversation.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.dto.AgentPreviewRequest;
import com.example.agentx.application.conversation.dto.ChatRequest;
import com.example.agentx.application.conversation.dto.ChatResponse;
import com.example.agentx.application.conversation.dto.MessageDTO;
import com.example.agentx.application.rag.dto.RagSessionDTO;
import com.example.agentx.application.rag.dto.RagStreamChatRequest;
import com.example.agentx.domain.agent.model.AgentWidgetEntity;
import com.example.agentx.interfaces.dto.agent.request.WidgetChatRequest;
import com.example.agentx.interfaces.dto.conversation.QueryConversationMessageRequest;

import java.util.List;

/** 对话应用服务聚合入口，按用例委托给具体协作服务 */
@Service
public class ConversationAppService {
    private final ConversationMessageQueryAppService messageQueryAppService;
    private final StandardConversationAppService standardConversationAppService;
    private final PreviewConversationAppService previewConversationAppService;
    private final WidgetConversationAppService widgetConversationAppService;
    private final RagConversationAppService ragConversationAppService;

    public ConversationAppService(ConversationMessageQueryAppService messageQueryAppService,
            StandardConversationAppService standardConversationAppService,
            PreviewConversationAppService previewConversationAppService,
            WidgetConversationAppService widgetConversationAppService,
            RagConversationAppService ragConversationAppService) {
        this.messageQueryAppService = messageQueryAppService;
        this.standardConversationAppService = standardConversationAppService;
        this.previewConversationAppService = previewConversationAppService;
        this.widgetConversationAppService = widgetConversationAppService;
        this.ragConversationAppService = ragConversationAppService;
    }

    public List<MessageDTO> getConversationMessages(String sessionId, String userId) {
        return messageQueryAppService.getConversationMessages(sessionId, userId);
    }

    public Page<MessageDTO> getConversationMessagesPage(String sessionId, String userId,
            QueryConversationMessageRequest request) {
        return messageQueryAppService.getConversationMessagesPage(sessionId, userId, request);
    }

    public SseEmitter chat(ChatRequest chatRequest, String userId) {
        return standardConversationAppService.chat(chatRequest, userId);
    }

    public SseEmitter chatWithModel(ChatRequest chatRequest, String userId, String modelId) {
        return standardConversationAppService.chatWithModel(chatRequest, userId, modelId);
    }

    public ChatResponse chatSyncWithModel(ChatRequest chatRequest, String userId, String modelId) {
        return standardConversationAppService.chatSyncWithModel(chatRequest, userId, modelId);
    }

    public SseEmitter previewAgent(AgentPreviewRequest previewRequest, String userId) {
        return previewConversationAppService.previewAgent(previewRequest, userId);
    }

    public SseEmitter widgetChat(String publicId, WidgetChatRequest widgetChatRequest, AgentWidgetEntity widgetEntity) {
        return widgetConversationAppService.widgetChat(publicId, widgetChatRequest, widgetEntity);
    }

    public ChatResponse widgetChatSync(String publicId, WidgetChatRequest widgetChatRequest,
            AgentWidgetEntity widgetEntity) {
        return widgetConversationAppService.widgetChatSync(publicId, widgetChatRequest, widgetEntity);
    }

    public SseEmitter ragStreamChat(RagStreamChatRequest request, String userId) {
        var ragRequest = ragConversationAppService.buildRagStreamChatRequest(request, userId);
        var ragContext = ragConversationAppService.prepareRagEnvironment(ragRequest, userId);
        return standardConversationAppService.chat(ragContext, ragRequest);
    }

    public SseEmitter ragStreamChatByUserRag(RagStreamChatRequest request, String userRagId, String userId) {
        var ragRequest = ragConversationAppService.buildUserRagStreamChatRequest(request, userRagId, userId);
        var ragContext = ragConversationAppService.prepareRagEnvironment(ragRequest, userId);
        return standardConversationAppService.chat(ragContext, ragRequest);
    }

    public RagSessionDTO createNewRagSession(String userId) {
        return ragConversationAppService.createNewRagSession(userId);
    }

    public RagSessionDTO createNewUserRagSession(String userRagId, String userId) {
        return ragConversationAppService.createNewUserRagSession(userRagId, userId);
    }

    public void closeRagSession(String sessionId, String userId) {
        ragConversationAppService.closeRagSession(sessionId, userId);
    }
}
