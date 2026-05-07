package com.example.agentx.interfaces.api.portal.agent;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.example.agentx.application.agent.service.AgentSessionAppService;
import com.example.agentx.application.conversation.dto.AgentPreviewRequest;
import com.example.agentx.application.conversation.dto.ChatRequest;
import com.example.agentx.application.conversation.service.ConversationAppService;
import com.example.agentx.application.conversation.service.ChatSessionManager;
import com.example.agentx.application.conversation.dto.MessageDTO;
import com.example.agentx.application.conversation.dto.SessionDTO;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;
import com.example.agentx.interfaces.dto.conversation.QueryConversationMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Agent会话管理 */
@RestController
@RequestMapping("/agents/sessions")
@Validated
public class PortalAgentSessionController {

    private final Logger logger = LoggerFactory.getLogger(PortalAgentSessionController.class);
    private final AgentSessionAppService agentSessionAppService;
    private final ConversationAppService conversationAppService;
    private final ChatSessionManager chatSessionManager;
    private final SessionDomainService sessionDomainService;

    public PortalAgentSessionController(AgentSessionAppService agentSessionAppService,
            ConversationAppService conversationAppService, ChatSessionManager chatSessionManager,
            SessionDomainService sessionDomainService) {
        this.agentSessionAppService = agentSessionAppService;
        this.conversationAppService = conversationAppService;
        this.chatSessionManager = chatSessionManager;
        this.sessionDomainService = sessionDomainService;
    }

    /** 获取会话中的消息列表 */
    @GetMapping("/{sessionId}/messages")
    public Result<List<MessageDTO>> getConversationMessages(@PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();
        return Result.success(conversationAppService.getConversationMessages(sessionId, userId));
    }

    /** 分页获取会话消息（默认最近30条） */
    @GetMapping("/{sessionId}/messages/page")
    public Result<Page<MessageDTO>> getConversationMessagesPage(@PathVariable String sessionId,
            QueryConversationMessageRequest request) {
        String userId = UserContext.getCurrentUserId();
        return Result.success(conversationAppService.getConversationMessagesPage(sessionId, userId, request));
    }

    /** 获取助理会话列表 */
    @GetMapping("/{agentId}")
    public Result<List<SessionDTO>> getAgentSessionList(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        return Result.success(agentSessionAppService.getAgentSessionList(userId, agentId));
    }

    /** 创建会话 */
    @PostMapping("/{agentId}")
    public Result<SessionDTO> createSession(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        return Result.success(agentSessionAppService.createSession(userId, agentId));
    }

    /** 提前预热 Agent 绑定的 MCP 工具，用于页面打开或 Agent 切换时降低首轮等待。 */
    @PostMapping("/{agentId}/prewarm")
    public Result<Void> prewarmAgentTools(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        agentSessionAppService.prewarmAgentToolsAsync(userId, agentId);
        return Result.success();
    }

    /** 更新会话 */
    @PutMapping("/{id}")
    public Result<Void> updateSession(@PathVariable String id,
            @RequestParam @NotBlank(message = "会话标题不能为空") @Size(max = 20, message = "会话标题不能超过20个字") String title) {
        String userId = UserContext.getCurrentUserId();
        agentSessionAppService.updateSession(id, userId, title);
        return Result.success();
    }

    /** 删除会话 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteSession(@PathVariable String id) {
        String userId = UserContext.getCurrentUserId();
        agentSessionAppService.deleteSession(id, userId);
        return Result.success();
    }

    /** 发送消息
     * @param chatRequest 消息对象
     * @return */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Validated ChatRequest chatRequest) {
        return conversationAppService.chat(chatRequest, UserContext.getCurrentUserId());
    }

    /** Agent预览功能 用于在创建/编辑Agent时预览对话效果，无需保存会话
     * @param previewRequest 预览请求对象
     * @return SSE流 */
    @PostMapping(value = "/preview", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter preview(@RequestBody AgentPreviewRequest previewRequest) {
        String userId = UserContext.getCurrentUserId();
        return conversationAppService.previewAgent(previewRequest, userId);
    }

    /** 中断对话会话
     * @param sessionId 会话ID
     * @return 中断结果 */
    @PostMapping("/{sessionId}/interrupt")
    public Result<String> interruptSession(@PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();

        logger.info("用户 {} 请求中断会话: {}", userId, sessionId);
        sessionDomainService.getSession(sessionId, userId);

        boolean success = chatSessionManager.interruptSession(sessionId);

        if (success) {
            logger.info("成功中断会话: sessionId={}, userId={}", sessionId, userId);
            return Result.success("对话已中断");
        } else {
            logger.warn("中断会话失败，会话不存在: sessionId={}, userId={}", sessionId, userId);
            return Result.success("会话已结束或不存在");
        }
    }
}
