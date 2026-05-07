package com.example.agentx.interfaces.api.portal.rag;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.rag.dto.DocumentUnitDTO;
import com.example.agentx.application.rag.dto.RagSessionDTO;
import com.example.agentx.application.rag.dto.RagSearchRequest;
import com.example.agentx.application.rag.dto.RagStreamChatRequest;
import com.example.agentx.application.conversation.service.ConversationAppService;
import com.example.agentx.application.rag.service.search.RAGSearchAppService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;

import java.util.List;

/** RAG搜索控制器
 * @author shilong.zang
 * @date 2024-12-09 */
@RestController
@RequestMapping("/rag/search")
public class RagSearchController {

    private final RAGSearchAppService ragSearchAppService;
    private final ConversationAppService conversationAppService;

    public RagSearchController(RAGSearchAppService ragSearchAppService, ConversationAppService conversationAppService) {
        this.ragSearchAppService = ragSearchAppService;
        this.conversationAppService = conversationAppService;
    }

    /** RAG搜索文档
     * 
     * @param request RAG搜索请求
     * @return 搜索结果 */
    @PostMapping
    public Result<List<DocumentUnitDTO>> ragSearch(@RequestBody @Validated RagSearchRequest request) {
        String userId = UserContext.getCurrentUserId();
        List<DocumentUnitDTO> searchResults = ragSearchAppService.ragSearch(request, userId);
        return Result.success(searchResults);
    }

    /** 创建新的RAG对话会话
     *
     * @return 新会话ID */
    @PostMapping("/session")
    public Result<RagSessionDTO> createRagSession() {
        String userId = UserContext.getCurrentUserId();
        return Result.success(conversationAppService.createNewRagSession(userId));
    }

    /** 关闭RAG对话会话
     *
     * @param sessionId 会话ID */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> closeRagSession(@PathVariable String sessionId) {
        String userId = UserContext.getCurrentUserId();
        conversationAppService.closeRagSession(sessionId, userId);
        return Result.success();
    }

    /** RAG流式问答 - 使用统一架构
     * 
     * @param request 流式问答请求
     * @return 流式响应 */
    @PostMapping(value = "/stream-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ragStreamChat(@RequestBody @Validated RagStreamChatRequest request) {
        String userId = UserContext.getCurrentUserId();
        return conversationAppService.ragStreamChat(request, userId);
    }

    /** 基于已安装知识库的RAG搜索
     * 
     * @param userRagId 已安装的知识库ID
     * @param request RAG搜索请求
     * @return 搜索结果 */
    @PostMapping("/user-rag/{userRagId}")
    public Result<List<DocumentUnitDTO>> ragSearchByUserRag(@PathVariable String userRagId,
            @RequestBody @Validated RagSearchRequest request) {
        String userId = UserContext.getCurrentUserId();
        List<DocumentUnitDTO> searchResults = ragSearchAppService.ragSearchByUserRag(request, userRagId, userId);
        return Result.success(searchResults);
    }

    /** 创建新的用户知识库RAG对话会话
     *
     * @param userRagId 已安装知识库ID
     * @return 新会话ID */
    @PostMapping("/user-rag/{userRagId}/session")
    public Result<RagSessionDTO> createUserRagSession(@PathVariable String userRagId) {
        String userId = UserContext.getCurrentUserId();
        return Result.success(conversationAppService.createNewUserRagSession(userRagId, userId));
    }

    /** 基于已安装知识库的RAG流式问答 - 使用统一架构
     * 
     * @param userRagId 已安装的知识库ID
     * @param request 流式问答请求
     * @return 流式响应 */
    @PostMapping(value = "/user-rag/{userRagId}/stream-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ragStreamChatByUserRag(@PathVariable String userRagId,
            @RequestBody @Validated RagStreamChatRequest request) {
        String userId = UserContext.getCurrentUserId();
        return conversationAppService.ragStreamChatByUserRag(request, userRagId, userId);
    }

}
