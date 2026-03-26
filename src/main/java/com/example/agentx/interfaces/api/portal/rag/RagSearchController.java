package com.example.agentx.interfaces.api.portal.rag;

import com.example.agentx.application.rag.dto.DocumentUnitDTO;
import com.example.agentx.application.rag.dto.RagSearchRequest;
import com.example.agentx.application.rag.dto.RagStreamChatRequest;
import com.example.agentx.application.rag.service.RagQaDatasetAppService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.interfaces.api.common.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG搜索控制器
 */
@RestController
@RequestMapping("/rag/search")
public class RagSearchController {

    private final RagQaDatasetAppService ragQaDatasetAppService;

    public RagSearchController(RagQaDatasetAppService ragQaDatasetAppService) {
        this.ragQaDatasetAppService = ragQaDatasetAppService;
    }

    /**
     * RAG搜索文档
     *
     * @param request RAG搜索请求
     * @return 搜索结果
     */
    @PostMapping
    public Result<List<DocumentUnitDTO>> ragSearch(@RequestBody @Validated RagSearchRequest request) {
        String userId = UserContext.getCurrentUserId();
        List<DocumentUnitDTO> searchResults = ragQaDatasetAppService.ragSearch(request, userId);
        return Result.success(searchResults);
    }

    /**
     * RAG流式问答
     *
     * @param request 流式问答请求
     * @return 流式响应
     */
    @PostMapping("/stream-chat")
    public SseEmitter ragStreamChat(@RequestBody @Validated RagStreamChatRequest request) {
        String userId = UserContext.getCurrentUserId();
        return ragQaDatasetAppService.ragStreamChat(request, userId);
    }

    /**
     * 基于已安装知识库的RAG搜索
     *
     * @param userRagId 已安装的知识库ID
     * @param request   RAG搜索请求
     * @return 搜索结果
     */
    @PostMapping("/user-rag/{userRagId}")
    public Result<List<DocumentUnitDTO>> ragSearchByUserRag(@PathVariable String userRagId,
                                                            @RequestBody @Validated RagSearchRequest request) {
        String userId = UserContext.getCurrentUserId();
        List<DocumentUnitDTO> searchResults = ragQaDatasetAppService.ragSearchByUserRag(request, userRagId, userId);
        return Result.success(searchResults);
    }

    /**
     * 基于已安装知识库的RAG流式问答
     *
     * @param userRagId 已安装的知识库ID
     * @param request   流式问答请求
     * @return 流式响应
     */
    @PostMapping("/user-rag/{userRagId}/stream-chat")
    public SseEmitter ragStreamChatByUserRag(@PathVariable String userRagId,
                                             @RequestBody @Validated RagStreamChatRequest request) {
        String userId = UserContext.getCurrentUserId();
        return ragQaDatasetAppService.ragStreamChatByUserRag(request, userRagId, userId);
    }

}