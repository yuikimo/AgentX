package com.example.agentx.application.conversation.service.message.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import com.example.agentx.application.conversation.dto.AgentChatResponse;
import com.example.agentx.application.conversation.dto.RagRetrievalDocumentDTO;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.application.rag.assembler.DocumentUnitAssembler;
import com.example.agentx.application.rag.dto.DocumentUnitDTO;
import com.example.agentx.application.rag.dto.RagSearchRequest;
import com.example.agentx.application.rag.service.search.RAGSearchAppService;
import com.example.agentx.domain.conversation.constant.MessageType;
import com.example.agentx.domain.prompt.RagPromptTemplates;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.constant.ConfidenceTier;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.repository.DocumentUnitRepository;
import com.example.agentx.domain.rag.service.DocumentUnitDomainService;
import com.example.agentx.domain.rag.service.FileDetailDomainService;
import com.example.agentx.infrastructure.transport.MessageTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class RagRetrievalCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(RagRetrievalCoordinator.class);

    private final RAGSearchAppService ragSearchAppService;
    private final ObjectMapper objectMapper;
    private final FileDetailDomainService fileDetailDomainService;
    private final RagQueryRewriter ragQueryRewriter;
    private final DocumentUnitDomainService documentUnitDomainService;
    private final RagProperties ragProperties;
    private final TaskExecutor ragSearchGroupTaskExecutor;

    public RagRetrievalCoordinator(RAGSearchAppService ragSearchAppService, ObjectMapper objectMapper,
            FileDetailDomainService fileDetailDomainService, RagQueryRewriter ragQueryRewriter,
            DocumentUnitDomainService documentUnitDomainService, RagProperties ragProperties,
            @Qualifier("ragSearchGroupTaskExecutor") TaskExecutor ragSearchGroupTaskExecutor) {
        this.ragSearchAppService = ragSearchAppService;
        this.objectMapper = objectMapper;
        this.fileDetailDomainService = fileDetailDomainService;
        this.ragQueryRewriter = ragQueryRewriter;
        this.documentUnitDomainService = documentUnitDomainService;
        this.ragProperties = ragProperties;
        this.ragSearchGroupTaskExecutor = ragSearchGroupTaskExecutor;
    }

    public <T> RagRetrievalResult performRetrieval(RagChatContext ragContext, MessageTransport<T> transport,
            T connection) {
        try {
            long retrievalStart = System.currentTimeMillis();
            transport.sendMessage(connection,
                    AgentChatResponse.build(RagPromptTemplates.retrievalStartMessage(), MessageType.RAG_RETRIEVAL_START));

            RagSearchRequest searchRequest = buildSearchRequest(ragContext);
            sendRetrievalProgress(transport, connection, "rewritten", buildRewriteProgressPayload(ragContext));
            RAGSearchAppService.SearchExecutionSummary searchSummary = searchDocumentsDetailed(ragContext, searchRequest,
                    buildSearchProgressListener(transport, connection, ragContext));
            List<DocumentUnitDTO> fullRetrievedDocuments = searchSummary.getDocuments();
            int originalDocumentCount = fullRetrievedDocuments == null ? 0 : fullRetrievedDocuments.size();
            fullRetrievedDocuments = enrichWithAdjacentDocuments(fullRetrievedDocuments);
            int adjacentAdded = Math.max(0, fullRetrievedDocuments.size() - originalDocumentCount);
            if (adjacentAdded > 0) {
                sendRetrievalProgress(transport, connection, "adjacent_done",
                        Map.of("adjacentAdded", adjacentAdded, "totalDocuments", fullRetrievedDocuments.size()));
            }
            assignCitationIds(fullRetrievedDocuments);
            List<RagRetrievalDocumentDTO> lightweightDocuments = convertToLightweightDTOs(fullRetrievedDocuments);
            sendRetrievalDocumentBatches(transport, connection, lightweightDocuments);

            String retrievalMessage = RagPromptTemplates.retrievalCompletedMessage(lightweightDocuments.size());
            AgentChatResponse retrievalEndResponse = AgentChatResponse.build(retrievalMessage,
                    MessageType.RAG_RETRIEVAL_END);
            try {
                retrievalEndResponse.setPayload(objectMapper.writeValueAsString(buildRetrievalEndPayload(lightweightDocuments,
                        searchSummary.getFailedGroups(), ragContext)));
            } catch (Exception e) {
                logger.error("序列化检索文档失败", e);
            }
            transport.sendMessage(connection, retrievalEndResponse);

            RagRetrievalResult retrievalResult = new RagRetrievalResult(fullRetrievedDocuments, retrievalMessage);
            retrievalResult.setRetrievalTime(System.currentTimeMillis() - retrievalStart);
            retrievalResult.setOriginalQuestion(StringUtils.defaultString(ragContext.getUserMessage()).trim());
            retrievalResult.setEffectiveQuestion(StringUtils.defaultString(ragContext.getRetrievalQuery()).trim());
            retrievalResult.setRewriteApplied(ragContext.isRewrittenQuery());
            retrievalResult.setFailedGroups(searchSummary.getFailedGroups());
            logger.info(
                    "RAG检索完成: sessionId={}, userId={}, docs={}, failedGroups={}, rewriteApplied={}, timeMs={}, queryLength={}",
                    ragContext.getSessionId(), ragContext.getUserId(), fullRetrievedDocuments.size(),
                    searchSummary.getFailedGroups().size(),
                    ragContext.isRewrittenQuery(), retrievalResult.getRetrievalTime(),
                    StringUtils.length(ragContext.getRetrievalQuery()));
            return retrievalResult;
        } catch (Exception e) {
            logger.error("RAG检索失败", e);
            transport.sendMessage(connection, ChatErrorResponseFactory.fromThrowable(e));
            RagRetrievalResult failedResult = new RagRetrievalResult(Collections.emptyList(), "检索失败", false);
            failedResult.setOriginalQuestion(StringUtils.defaultString(ragContext.getUserMessage()).trim());
            failedResult.setEffectiveQuestion(StringUtils.defaultString(ragContext.getRetrievalQuery()).trim());
            failedResult.setRewriteApplied(ragContext.isRewrittenQuery());
            return failedResult;
        }
    }

    private RagSearchRequest buildSearchRequest(RagChatContext ragContext) {
        String originalQuestion = StringUtils.defaultString(ragContext.getUserMessage()).trim();
        CompletableFuture<com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan> originalHydeFuture =
                startHydeWarmupFuture(ragContext, originalQuestion);
        boolean rewritePreferred = ragQueryRewriter.shouldRewriteQuestion(ragContext);
        CompletableFuture<RagQueryRewriter.RewriteExecutionResult> rewriteFuture = rewritePreferred
                ? ragQueryRewriter.rewriteQuestionWithStatusAsync(ragContext)
                : null;
        RagQueryRewriter.RewriteExecutionResult rewriteResult = rewritePreferred
                ? ragQueryRewriter.awaitRewriteQuestion(rewriteFuture, originalQuestion,
                        ragProperties.getQueryRewrite().getGraceWaitMs())
                : RagQueryRewriter.RewriteExecutionResult.skipped(originalQuestion, "rewrite_not_needed", 0L);
        String rewrittenQuestion = rewriteResult.getEffectiveQuestion();
        boolean rewriteApplied = rewriteResult.isRewriteApplied();
        RagSearchRequest searchRequest = copySearchRequest(ragContext.getRagSearchRequest(), rewrittenQuestion);
        if (Boolean.TRUE.equals(searchRequest.getEnableHyde()) && !rewriteApplied) {
            com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan precomputedPlan =
                    tryResolveHydePlanNonBlocking(originalHydeFuture);
            if (precomputedPlan != null) {
                searchRequest.setPrecomputedHydeQueryPlan(precomputedPlan);
            }
        } else if (Boolean.TRUE.equals(searchRequest.getEnableHyde()) && rewriteApplied
                && StringUtils.isNotBlank(rewrittenQuestion)
                && !StringUtils.equalsIgnoreCase(rewrittenQuestion, originalQuestion)) {
            // 改写生效时继续异步预热一次改写后的HyDE，尽量与后续检索重叠。
            ragSearchAppService.prepareHydeQueryPlanAsync(ragContext.getUserId(), rewrittenQuestion);
        }
        ragContext.setRetrievalQuery(StringUtils.isNotBlank(rewrittenQuestion) ? rewrittenQuestion : originalQuestion);
        ragContext.setRewrittenQuery(rewriteApplied);
        ragContext.setRewriteTimedOut(rewriteResult.isTimedOut());
        ragContext.setRewriteLatencyMs(rewriteResult.getLatencyMs());
        ragContext.setRewriteFallbackReason(rewriteResult.getFallbackReason());
        return searchRequest;
    }

    private CompletableFuture<com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan> startHydeWarmupFuture(
            RagChatContext ragContext, String originalQuestion) {
        if (ragContext == null || ragContext.getRagSearchRequest() == null
                || !Boolean.TRUE.equals(ragContext.getRagSearchRequest().getEnableHyde())
                || StringUtils.isBlank(originalQuestion)) {
            return null;
        }
        return ragSearchAppService.prepareHydeQueryPlanAsync(ragContext.getUserId(), originalQuestion);
    }

    private com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan tryResolveHydePlanNonBlocking(
            CompletableFuture<com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan> hydeFuture) {
        if (hydeFuture == null) {
            return null;
        }
        try {
            return hydeFuture.get(80, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T> RAGSearchAppService.SearchExecutionSummary searchDocumentsDetailed(RagChatContext ragContext,
            RagSearchRequest searchRequest, RAGSearchAppService.SearchProgressListener progressListener) {
        if (ragContext.getUserRagId() != null) {
            return ragSearchAppService.ragSearchByUserRagDetailed(searchRequest, ragContext.getUserRagId(),
                    ragContext.getUserId(), progressListener);
        }
        return ragSearchAppService.ragSearchDetailed(searchRequest, ragContext.getUserId(), progressListener);
    }

    private List<DocumentUnitDTO> enrichWithAdjacentDocuments(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        AdjacentLookupPlan lookupPlan = buildAdjacentLookupPlan(documents);
        if (lookupPlan.isEmpty()) {
            return documents;
        }
        try {
            List<DocumentUnitEntity> adjacentEntities = loadAdjacentEntities(lookupPlan);
            List<DocumentUnitDTO> adjacentDocuments = DocumentUnitAssembler.toDTOs(adjacentEntities);
            if (adjacentDocuments.isEmpty()) {
                return documents;
            }
            Map<String, DocumentUnitDTO> sourceByChunk = buildBestSourceByChunk(documents);
            Map<String, DocumentUnitDTO> sourceByPage = buildBestSourceByPage(documents);
            Set<String> seenIds = documents.stream().filter(Objects::nonNull).map(DocumentUnitDTO::getId)
                    .filter(StringUtils::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
            List<DocumentUnitDTO> enriched = new ArrayList<>(documents);
            int added = 0;
            for (DocumentUnitDTO adjacent : adjacentDocuments) {
                if (adjacent == null || StringUtils.isBlank(adjacent.getId()) || !seenIds.add(adjacent.getId())) {
                    continue;
                }
                DocumentUnitDTO source = null;
                if (adjacent.getChunkIndex() != null) {
                    source = sourceByChunk.get(chunkKey(adjacent.getFileId(), adjacent.getChunkIndex()));
                }
                if (source == null) {
                    source = sourceByPage.get(pageKey(adjacent.getFileId(), adjacent.getPage()));
                }
                if (source != null) {
                    adjacent.setSimilarityScore(resolveAdjacentScore(source, adjacent));
                    adjacent.setConfidenceTier(source.getConfidenceTier());
                    adjacent.setFilename(source.getFilename());
                }
                enriched.add(adjacent);
                added++;
                if (added >= resolveMaxAdjacentDocuments()) {
                    break;
                }
            }
            if (added > 0) {
                logger.info("RAG检索补充相邻片段: originalDocs={}, adjacentAdded={}", documents.size(), added);
            }
            return enriched;
        } catch (Exception e) {
            logger.warn("RAG检索补充相邻片段失败: docs={}, err={}", documents.size(), e.getMessage());
            return documents;
        }
    }

    private <T> RAGSearchAppService.SearchProgressListener buildSearchProgressListener(MessageTransport<T> transport,
            T connection, RagChatContext ragContext) {
        return new RAGSearchAppService.SearchProgressListener() {
            @Override
            public void onHydePrepared(com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan hydeQueryPlan,
                    int totalGroups) {
                sendRetrievalProgress(transport, connection, "hyde", buildHydeProgressPayload(hydeQueryPlan, totalGroups));
            }

            @Override
            public void onGroupCompleted(int completedGroups, int totalGroups, List<String> datasetIds, boolean success,
                    boolean noResults, int documentCount, String errorCode, String errorMessage) {
                sendRetrievalProgress(transport, connection, "group_done",
                        buildGroupProgressPayload(completedGroups, totalGroups, datasetIds, success, noResults,
                                documentCount, errorCode, errorMessage));
            }

            @Override
            public void onRerankCompleted(int inputCount, int outputCount, boolean applied) {
                sendRetrievalProgress(transport, connection, "rerank_done",
                        buildRerankProgressPayload(inputCount, outputCount, applied, ragContext));
            }
        };
    }

    private List<DocumentUnitEntity> loadAdjacentEntities(AdjacentLookupPlan lookupPlan) {
        Map<String, DocumentUnitEntity> merged = new LinkedHashMap<>();

        CompletableFuture<List<DocumentUnitEntity>> chunkFuture = supplyAdjacentLookup(
                () -> documentUnitDomainService.selectAdjacentChunksByChunkIndexes(lookupPlan.chunkRefs()));
        CompletableFuture<List<DocumentUnitEntity>> missingPageFuture = supplyAdjacentLookup(
                () -> documentUnitDomainService.selectAdjacentChunks(lookupPlan.missingChunkPageRefs()));

        List<DocumentUnitEntity> chunkMatched = joinAdjacentLookup(chunkFuture);
        mergeAdjacentEntities(merged, chunkMatched);

        boolean chunkMissed = chunkMatched == null || chunkMatched.isEmpty();
        if (chunkMissed) {
            mergeAdjacentEntities(merged,
                    documentUnitDomainService.selectAdjacentChunks(lookupPlan.chunkFallbackPageRefs()));
        }

        mergeAdjacentEntities(merged, joinAdjacentLookup(missingPageFuture));
        return new ArrayList<>(merged.values());
    }

    private CompletableFuture<List<DocumentUnitEntity>> supplyAdjacentLookup(
            java.util.function.Supplier<List<DocumentUnitEntity>> supplier) {
        return CompletableFuture.supplyAsync(supplier, ragSearchGroupTaskExecutor);
    }

    private List<DocumentUnitEntity> joinAdjacentLookup(CompletableFuture<List<DocumentUnitEntity>> future) {
        try {
            return future == null ? Collections.emptyList() : future.join();
        } catch (Exception e) {
            logger.warn("RAG相邻片段并行查询失败: err={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void mergeAdjacentEntities(Map<String, DocumentUnitEntity> target, List<DocumentUnitEntity> additions) {
        if (target == null || additions == null || additions.isEmpty()) {
            return;
        }
        for (DocumentUnitEntity entity : additions) {
            if (entity == null || StringUtils.isBlank(entity.getId())) {
                continue;
            }
            target.putIfAbsent(entity.getId(), entity);
        }
    }

    private AdjacentLookupPlan buildAdjacentLookupPlan(List<DocumentUnitDTO> documents) {
        List<DocumentUnitDTO> seedDocuments = selectAdjacentSeedDocuments(documents);
        if (seedDocuments.isEmpty()) {
            return AdjacentLookupPlan.empty();
        }

        List<DocumentUnitRepository.FileChunkRef> chunkRefs = new ArrayList<>();
        List<DocumentUnitRepository.FilePageRef> chunkFallbackPageRefs = new ArrayList<>();
        List<DocumentUnitRepository.FilePageRef> missingChunkPageRefs = new ArrayList<>();
        Set<String> chunkScheduled = new LinkedHashSet<>();
        Set<String> chunkFallbackScheduled = new LinkedHashSet<>();
        Set<String> missingChunkScheduled = new LinkedHashSet<>();

        for (DocumentUnitDTO document : seedDocuments) {
            if (document == null || StringUtils.isBlank(document.getFileId())) {
                continue;
            }
            Integer chunkIndex = document.getChunkIndex();
            if (chunkIndex != null) {
                addAdjacentChunkRefs(chunkRefs, chunkScheduled, document.getFileId(), chunkIndex);
                addAdjacentPageRefs(chunkFallbackPageRefs, chunkFallbackScheduled, document.getFileId(),
                        document.getPage());
            } else {
                addAdjacentPageRefs(missingChunkPageRefs, missingChunkScheduled, document.getFileId(),
                        document.getPage());
            }
        }
        return new AdjacentLookupPlan(chunkRefs, chunkFallbackPageRefs, missingChunkPageRefs);
    }

    private List<DocumentUnitDTO> selectAdjacentSeedDocuments(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        return documents.stream().filter(Objects::nonNull)
                .filter(document -> StringUtils.isNotBlank(document.getFileId())
                        && (document.getChunkIndex() != null || document.getPage() != null))
                .sorted(Comparator.comparing((DocumentUnitDTO document) -> tierPriority(document.getConfidenceTier()))
                        .reversed()
                        .thenComparing(DocumentUnitDTO::getSimilarityScore,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(resolveMaxAdjacentSeeds()).toList();
    }

    private void addAdjacentChunkRefs(List<DocumentUnitRepository.FileChunkRef> refs, Set<String> scheduled,
            String fileId, Integer chunkIndex) {
        if (StringUtils.isBlank(fileId) || chunkIndex == null) {
            return;
        }
        for (int targetChunk = chunkIndex - 1; targetChunk <= chunkIndex + 1; targetChunk++) {
            if (targetChunk < 0 || targetChunk == chunkIndex) {
                continue;
            }
            String key = chunkKey(fileId, targetChunk);
            if (scheduled.add(key)) {
                refs.add(new DocumentUnitRepository.FileChunkRef(fileId, targetChunk));
            }
        }
    }

    private void addAdjacentPageRefs(List<DocumentUnitRepository.FilePageRef> refs, Set<String> scheduled, String fileId,
            Integer page) {
        if (StringUtils.isBlank(fileId) || page == null) {
            return;
        }
        for (int targetPage = page - 1; targetPage <= page + 1; targetPage++) {
            if (targetPage < 0 || targetPage == page) {
                continue;
            }
            String key = pageKey(fileId, targetPage);
            if (scheduled.add(key)) {
                refs.add(new DocumentUnitRepository.FilePageRef(fileId, targetPage));
            }
        }
    }

    private int resolveMaxAdjacentSeeds() {
        return Math.max(1, ragProperties.getRetrieval().getAdjacent().getMaxSeeds());
    }

    private int resolveMaxAdjacentDocuments() {
        return Math.max(1, ragProperties.getRetrieval().getAdjacent().getMaxDocuments());
    }

    private Map<String, DocumentUnitDTO> buildBestSourceByChunk(List<DocumentUnitDTO> documents) {
        Map<String, DocumentUnitDTO> sourceByChunk = new LinkedHashMap<>();
        for (DocumentUnitDTO document : documents) {
            if (document == null || StringUtils.isBlank(document.getFileId()) || document.getChunkIndex() == null) {
                continue;
            }
            for (int chunkIndex = document.getChunkIndex() - 1; chunkIndex <= document.getChunkIndex() + 1; chunkIndex++) {
                if (chunkIndex >= 0 && !Objects.equals(chunkIndex, document.getChunkIndex())) {
                    sourceByChunk.putIfAbsent(chunkKey(document.getFileId(), chunkIndex), document);
                }
            }
        }
        return sourceByChunk;
    }

    private Map<String, DocumentUnitDTO> buildBestSourceByPage(List<DocumentUnitDTO> documents) {
        Map<String, DocumentUnitDTO> sourceByPage = new LinkedHashMap<>();
        for (DocumentUnitDTO document : documents) {
            if (document == null || StringUtils.isBlank(document.getFileId()) || document.getPage() == null) {
                continue;
            }
            for (int page = Math.max(0, document.getPage() - 1); page <= document.getPage() + 1; page++) {
                if (!Objects.equals(page, document.getPage())) {
                    sourceByPage.putIfAbsent(pageKey(document.getFileId(), page), document);
                }
            }
        }
        return sourceByPage;
    }

    private Double resolveAdjacentScore(DocumentUnitDTO source, DocumentUnitDTO adjacent) {
        double score = source != null && source.getSimilarityScore() != null ? source.getSimilarityScore() : 0.5;
        double multiplier = resolveAdjacentMultiplier(source, adjacent);
        return score * multiplier;
    }

    private double resolveAdjacentMultiplier(DocumentUnitDTO source, DocumentUnitDTO adjacent) {
        RagProperties.Adjacent adjacentProperties = ragProperties.getRetrieval().getAdjacent();
        double tierMultiplier = switch (source != null && source.getConfidenceTier() != null ? source.getConfidenceTier()
                : ConfidenceTier.FALLBACK) {
            case HIGH -> adjacentProperties.getHighConfidenceMultiplier();
            case LOW -> adjacentProperties.getLowConfidenceMultiplier();
            case FALLBACK -> adjacentProperties.getFallbackConfidenceMultiplier();
        };
        int distance = resolveAdjacentDistance(source, adjacent);
        double distanceMultiplier = tierMultiplier / Math.max(1, distance);
        return Math.max(adjacentProperties.getMinMultiplier(), distanceMultiplier);
    }

    private int resolveAdjacentDistance(DocumentUnitDTO source, DocumentUnitDTO adjacent) {
        if (source == null || adjacent == null) {
            return 1;
        }
        if (source.getChunkIndex() != null && adjacent.getChunkIndex() != null) {
            return Math.max(1, Math.abs(source.getChunkIndex() - adjacent.getChunkIndex()));
        }
        if (source.getPage() != null && adjacent.getPage() != null) {
            return Math.max(1, Math.abs(source.getPage() - adjacent.getPage()));
        }
        return 1;
    }

    private int tierPriority(ConfidenceTier confidenceTier) {
        return confidenceTier == null ? 0 : confidenceTier.getPriority();
    }

    private String pageKey(String fileId, Integer page) {
        return StringUtils.defaultString(fileId) + ":" + page;
    }

    private String chunkKey(String fileId, Integer chunkIndex) {
        return StringUtils.defaultString(fileId) + ":" + chunkIndex;
    }

    private record AdjacentLookupPlan(List<DocumentUnitRepository.FileChunkRef> chunkRefs,
            List<DocumentUnitRepository.FilePageRef> chunkFallbackPageRefs,
            List<DocumentUnitRepository.FilePageRef> missingChunkPageRefs) {

        private static AdjacentLookupPlan empty() {
            return new AdjacentLookupPlan(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        private boolean isEmpty() {
            boolean noChunkRefs = chunkRefs == null || chunkRefs.isEmpty();
            boolean noChunkFallbackPageRefs = chunkFallbackPageRefs == null || chunkFallbackPageRefs.isEmpty();
            boolean noMissingChunkPageRefs = missingChunkPageRefs == null || missingChunkPageRefs.isEmpty();
            return noChunkRefs && noChunkFallbackPageRefs && noMissingChunkPageRefs;
        }
    }

    private RagSearchRequest copySearchRequest(RagSearchRequest source, String question) {
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setDatasetIds(source.getDatasetIds());
        searchRequest.setQuestion(question);
        searchRequest.setMaxResults(source.getMaxResults());
        searchRequest.setMinScore(source.getMinScore());
        searchRequest.setFallbackMinScore(source.getFallbackMinScore());
        searchRequest.setEnableRerank(source.getEnableRerank());
        searchRequest.setCandidateMultiplier(source.getCandidateMultiplier());
        searchRequest.setTimeoutSeconds(source.getTimeoutSeconds());
        searchRequest.setEnableQueryExpansion(source.getEnableQueryExpansion());
        searchRequest.setEnableHyde(source.getEnableHyde());
        searchRequest.setPrecomputedHydeQueryPlan(source.getPrecomputedHydeQueryPlan());
        return searchRequest;
    }

    private List<RagRetrievalDocumentDTO> convertToLightweightDTOs(List<DocumentUnitDTO> documents) {
        List<RagRetrievalDocumentDTO> lightweightDTOs = new ArrayList<>();
        Map<String, String> fileNameMap = loadFileNameMap(documents);
        attachFileNames(documents, fileNameMap);

        for (DocumentUnitDTO doc : documents) {
            try {
                String fileName = fileNameMap.getOrDefault(doc.getFileId(), "未知文件");
                RagRetrievalDocumentDTO lightweight = new RagRetrievalDocumentDTO(doc.getFileId(), fileName, doc.getId(),
                        doc.getSimilarityScore() != null ? doc.getSimilarityScore() : 0.0, doc.getPage(),
                        doc.getConfidenceTier());
                lightweight.setCitationId(doc.getCitationId());
                lightweightDTOs.add(lightweight);
            } catch (Exception e) {
                logger.warn("转换轻量级DTO失败，文档ID: {}", doc.getId(), e);
                RagRetrievalDocumentDTO lightweight = new RagRetrievalDocumentDTO(doc.getFileId(), "未知文件", doc.getId(),
                        0.0, doc.getPage(), doc.getConfidenceTier());
                lightweight.setCitationId(doc.getCitationId());
                lightweightDTOs.add(lightweight);
            }
        }

        return lightweightDTOs;
    }

    private void assignCitationIds(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        Set<String> usedCitationIds = new LinkedHashSet<>();
        for (DocumentUnitDTO document : documents) {
            if (document == null) {
                continue;
            }
            document.setCitationId(resolveCitationId(document, usedCitationIds));
        }
    }

    private String resolveCitationId(DocumentUnitDTO document, Set<String> usedCitationIds) {
        String sourceId = document == null ? null : document.getId();
        String normalized = StringUtils.defaultIfBlank(sourceId, "unknown").replaceAll("[^A-Za-z0-9]", "");
        String baseId;
        if (StringUtils.isNotBlank(normalized)) {
            baseId = normalized.length() <= 6 ? normalized : normalized.substring(0, 6);
        } else {
            baseId = Integer.toHexString(StringUtils.defaultString(sourceId).hashCode());
        }
        baseId = baseId.toUpperCase(Locale.ROOT);
        String candidate = baseId;
        int suffix = 2;
        while (!usedCitationIds.add(candidate)) {
            candidate = baseId + suffix++;
        }
        return candidate;
    }

    private <T> void sendRetrievalProgress(MessageTransport<T> transport, T connection, String stage,
            Object payloadObject) {
        AgentChatResponse response = AgentChatResponse.build("", MessageType.RAG_RETRIEVAL_PROGRESS);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stage", stage);
            if (payloadObject instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        payload.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            response.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.warn("序列化RAG检索进度失败: stage={}, err={}", stage, e.getMessage());
        }
        transport.sendMessage(connection, response);
    }

    private <T> void sendRetrievalDocumentBatches(MessageTransport<T> transport, T connection,
            List<RagRetrievalDocumentDTO> lightweightDocuments) {
        if (lightweightDocuments == null || lightweightDocuments.isEmpty()) {
            return;
        }
        final int batchSize = 3;
        int totalBatches = (lightweightDocuments.size() + batchSize - 1) / batchSize;
        for (int index = 0; index < lightweightDocuments.size(); index += batchSize) {
            int batchNumber = index / batchSize + 1;
            List<RagRetrievalDocumentDTO> batch = lightweightDocuments.subList(index,
                    Math.min(index + batchSize, lightweightDocuments.size()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batch", batchNumber);
            payload.put("totalBatches", totalBatches);
            payload.put("documents", batch);
            payload.put("retrievedCount", lightweightDocuments.size());
            sendRetrievalProgress(transport, connection, "documents_batch", payload);
        }
    }

    private Map<String, Object> buildRewriteProgressPayload(RagChatContext ragContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rewriteApplied", ragContext != null && ragContext.isRewrittenQuery());
        payload.put("rewriteTimedOut", ragContext != null && ragContext.isRewriteTimedOut());
        payload.put("rewriteLatencyMs", ragContext == null ? 0L : ragContext.getRewriteLatencyMs());
        payload.put("rewriteFallbackReason",
                ragContext == null ? null : StringUtils.defaultIfBlank(ragContext.getRewriteFallbackReason(), null));
        payload.put("originalQuestion",
                ragContext == null ? "" : StringUtils.defaultString(ragContext.getUserMessage()).trim());
        payload.put("effectiveQuestion",
                ragContext == null ? "" : StringUtils.defaultString(ragContext.getRetrievalQuery()).trim());
        return payload;
    }

    private Map<String, Object> buildHydeProgressPayload(
            com.example.agentx.domain.rag.service.HyDEDomainService.HyDEQueryPlan hydeQueryPlan, int totalGroups) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queryHash", hydeQueryPlan == null ? "" : hydeQueryPlan.getQueryHash());
        payload.put("applied", hydeQueryPlan != null && hydeQueryPlan.isHydeApplied());
        payload.put("cacheHit", hydeQueryPlan != null && hydeQueryPlan.isCacheHit());
        payload.put("skipReason", hydeQueryPlan == null ? null : hydeQueryPlan.getSkipReason());
        payload.put("failureReason", hydeQueryPlan == null ? null : hydeQueryPlan.getFailureReason());
        payload.put("totalGroups", totalGroups);
        return payload;
    }

    private Map<String, Object> buildGroupProgressPayload(int completedGroups, int totalGroups, List<String> datasetIds,
            boolean success, boolean noResults, int documentCount, String errorCode, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completedGroups", completedGroups);
        payload.put("totalGroups", totalGroups);
        payload.put("datasetIds", datasetIds == null ? Collections.emptyList() : datasetIds);
        payload.put("documentCount", documentCount);
        payload.put("status", success ? "success" : noResults ? "no_results" : "failed");
        if (StringUtils.isNotBlank(errorCode)) {
            payload.put("errorCode", errorCode);
        }
        if (StringUtils.isNotBlank(errorMessage)) {
            payload.put("errorMessage", errorMessage);
        }
        return payload;
    }

    private Map<String, Object> buildRerankProgressPayload(int inputCount, int outputCount, boolean applied,
            RagChatContext ragContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applied", applied);
        payload.put("inputCount", inputCount);
        payload.put("count", outputCount);
        payload.put("effectiveQuestion",
                ragContext == null ? "" : StringUtils.defaultString(ragContext.getRetrievalQuery()).trim());
        return payload;
    }

    private Map<String, Object> buildRetrievalEndPayload(List<RagRetrievalDocumentDTO> lightweightDocuments,
            List<RagFailedGroupInfo> failedGroups, RagChatContext ragContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retrievedCount", lightweightDocuments == null ? 0 : lightweightDocuments.size());
        payload.put("documentsIncluded", false);
        payload.put("failedGroups", failedGroups == null ? Collections.emptyList() : failedGroups);
        payload.put("rewriteApplied", ragContext != null && ragContext.isRewrittenQuery());
        payload.put("effectiveQuestion",
                ragContext == null ? "" : StringUtils.defaultString(ragContext.getRetrievalQuery()).trim());
        return payload;
    }

    private void attachFileNames(List<DocumentUnitDTO> documents, Map<String, String> fileNameMap) {
        if (documents == null || documents.isEmpty() || fileNameMap == null || fileNameMap.isEmpty()) {
            return;
        }
        for (DocumentUnitDTO document : documents) {
            if (document == null || StringUtils.isBlank(document.getFileId())) {
                continue;
            }
            document.setFilename(fileNameMap.getOrDefault(document.getFileId(), document.getFilename()));
        }
    }

    private Map<String, String> loadFileNameMap(List<DocumentUnitDTO> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> fileIds = documents.stream().filter(Objects::nonNull).map(DocumentUnitDTO::getFileId)
                .filter(StringUtils::isNotBlank).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (fileIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return fileDetailDomainService.listFilesByIdsWithoutUserCheck(new ArrayList<>(fileIds)).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(FileDetailEntity::getId, this::resolveDisplayFileName,
                            (existing, replacement) -> existing, LinkedHashMap::new));
        } catch (Exception e) {
            logger.warn("批量查询RAG文件名失败: fileIds={}, err={}", fileIds, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String resolveDisplayFileName(FileDetailEntity fileDetail) {
        if (fileDetail == null) {
            return "未知文件";
        }
        if (StringUtils.isNotBlank(fileDetail.getOriginalFilename())) {
            return fileDetail.getOriginalFilename();
        }
        if (StringUtils.isNotBlank(fileDetail.getFilename())) {
            return fileDetail.getFilename();
        }
        return "未知文件";
    }
}
