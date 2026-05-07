package com.example.agentx.domain.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.rag.constant.SearchType;
import com.example.agentx.domain.rag.constant.ConfidenceTier;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.dto.HybridSearchConfig;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.model.VectorStoreResult;
import com.example.agentx.domain.rag.repository.DocumentUnitRepository;
import com.example.agentx.domain.rag.repository.FileDetailRepository;
import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 混合检索领域服务 协调向量检索和关键词检索，实现RRF融合算法
 * 
 * @author claude */
@Service
public class HybridSearchDomainService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchDomainService.class);

    /** 并行检索的默认总超时时间（秒） */
    private static final int DEFAULT_SEARCH_TIMEOUT_SECONDS = 30;

    private final EmbeddingDomainService embeddingDomainService;
    private final KeywordSearchDomainService keywordSearchDomainService;
    private final DocumentUnitRepository documentUnitRepository;
    private final FileDetailRepository fileDetailRepository;
    private final RerankDomainService rerankDomainService;
    private final HyDEDomainService hydeDomainService;
    private final RagProperties ragProperties;

    public HybridSearchDomainService(EmbeddingDomainService embeddingDomainService,
            KeywordSearchDomainService keywordSearchDomainService, DocumentUnitRepository documentUnitRepository,
            FileDetailRepository fileDetailRepository, RerankDomainService rerankDomainService,
            HyDEDomainService hydeDomainService, RagProperties ragProperties) {
        this.embeddingDomainService = embeddingDomainService;
        this.keywordSearchDomainService = keywordSearchDomainService;
        this.documentUnitRepository = documentUnitRepository;
        this.fileDetailRepository = fileDetailRepository;
        this.rerankDomainService = rerankDomainService;
        this.hydeDomainService = hydeDomainService;
        this.ragProperties = ragProperties;
    }

    /** 执行混合检索 并行执行向量检索和关键词检索，使用RRF算法融合结果
     *
     * @param config 混合检索配置对象
     * @return 混合检索结果列表 */
    public List<DocumentUnitEntity> hybridSearch(HybridSearchConfig config) {
        return hybridSearchWithStatus(config).getDocuments();
    }

    public HybridSearchExecutionResult hybridSearchWithStatus(HybridSearchConfig config) {

        // 参数验证
        if (config == null) {
            log.warn("混合搜索配置为空");
            return HybridSearchExecutionResult.failed(HybridSearchStatus.INVALID_CONFIG, "CONFIG_EMPTY", "混合搜索配置为空");
        }

        if (!config.isValid()) {
            String error = config.getValidationError();
            log.warn("无效的混合搜索配置: {}", error);
            return HybridSearchExecutionResult.failed(HybridSearchStatus.INVALID_CONFIG, "CONFIG_INVALID", error);
        }

        // 设置默认值
        int finalMaxResults = config.getMaxResults() != null
                ? Math.min(config.getMaxResults(), HybridSearchConfig.ABSOLUTE_MAX_RESULTS)
                : HybridSearchConfig.DEFAULT_MAX_RESULTS;
        Double finalMinScore = config.getMinScore() != null ? Math.max(0.0, Math.min(config.getMinScore(), 1.0)) : 0.7;
        int finalTimeoutSeconds = normalizeTimeoutSeconds(config.getTimeoutSeconds());
        int finalCandidateMultiplier = config.getCandidateMultiplier() == null ? 2
                : Math.max(1, Math.min(config.getCandidateMultiplier(), 5));
        int channelCandidateLimit = Math.min(HybridSearchConfig.ABSOLUTE_MAX_RESULTS,
                Math.max(finalMaxResults, finalMaxResults * finalCandidateMultiplier));

        long startTime = System.currentTimeMillis();
        String originalQuery = config.getQuestion();

        try {
            log.info("开始混合搜索 查询: '{}', 数据集: {}, 最大结果数: {}, HyDE可用: {}", config.getQuestion(),
                    config.getDataSetIds().size(), finalMaxResults, config.hasValidChatModelConfig());

            // HyDE处理：保留原始查询，仅向量检索使用“原查询 + 假设文档”
            HyDEDomainService.HyDEQueryPlan hydeQueryPlan = config.getHydeQueryPlan();
            if (hydeQueryPlan == null) {
                hydeQueryPlan = hydeDomainService.prepareQueryPlan(originalQuery, config.getChatModelConfig());
                log.info(
                        "HyDE plan prepared inside hybridSearch: queryHash={}, applied={}, cacheHit={}, skipReason={}, failureReason={}",
                        hydeQueryPlan.getQueryHash(), hydeQueryPlan.isHydeApplied(), hydeQueryPlan.isCacheHit(),
                        hydeQueryPlan.getSkipReason(), hydeQueryPlan.getFailureReason());
            }
            final HyDEDomainService.HyDEQueryPlan finalHydeQueryPlan = hydeQueryPlan;

            CompletableFuture<List<VectorStoreResult>> vectorSearchFuture = CompletableFuture.supplyAsync(() -> {
                if (finalHydeQueryPlan.isHydeApplied()) {
                    return embeddingDomainService.vectorSearchWithHydeFusion(config.getDataSetIds(),
                            finalHydeQueryPlan.getOriginalQuery(), finalHydeQueryPlan.getHypotheticalDocument(),
                            channelCandidateLimit, finalMinScore, config.getFallbackMinScore(), false,
                            finalCandidateMultiplier, config.getEmbeddingConfig(), config.getVectorTableName(),
                            config.getVectorDimension());
                }
                return embeddingDomainService.vectorSearch(config.getDataSetIds(), finalHydeQueryPlan.getVectorQuery(),
                        channelCandidateLimit, finalMinScore, config.getFallbackMinScore(), false,
                        finalCandidateMultiplier, config.getEmbeddingConfig(), config.getVectorTableName(),
                        config.getVectorDimension());
            });

            CompletableFuture<List<VectorStoreResult>> keywordSearchFuture = CompletableFuture
                    .supplyAsync(() -> keywordSearchDomainService.keywordSearch(config.getDataSetIds(),
                            finalHydeQueryPlan.getKeywordQuery(), channelCandidateLimit, config.getVectorTableName()));

            // 使用统一总超时预算等待两个任务，避免顺序双等导致体感翻倍
            waitForSearchChannels(vectorSearchFuture, keywordSearchFuture, originalQuery, finalTimeoutSeconds);
            SearchChannelResult vectorChannelResult = collectSearchChannelResult(vectorSearchFuture, "向量",
                    originalQuery);
            SearchChannelResult keywordChannelResult = collectSearchChannelResult(keywordSearchFuture, "关键词",
                    originalQuery);
            List<VectorStoreResult> vectorResults = vectorChannelResult.getResults();
            List<VectorStoreResult> keywordResults = keywordChannelResult.getResults();

            if (!vectorChannelResult.isSuccess() && !keywordChannelResult.isSuccess()) {
                log.warn("向量和关键词搜索均失败: query='{}', vectorErrorCode={}, keywordErrorCode={}", originalQuery,
                        vectorChannelResult.getErrorCode(), keywordChannelResult.getErrorCode());
                return HybridSearchExecutionResult.failed(HybridSearchStatus.SEARCH_ERROR, "ALL_CHANNELS_FAILED",
                        mergeErrorMessages(vectorChannelResult, keywordChannelResult));
            }

            if (vectorResults.isEmpty() && keywordResults.isEmpty()) {
                log.warn("向量和关键词搜索对于查询'{}'都无结果: vectorSuccess={}, keywordSuccess={}", originalQuery,
                        vectorChannelResult.isSuccess(), keywordChannelResult.isSuccess());
                return HybridSearchExecutionResult.noResults();
            }

            // 使用RRF算法融合结果
            List<VectorStoreResult> fusedResults = fusionWithRRF(vectorResults, keywordResults, finalMaxResults,
                    finalMinScore);

            // RRF融合后进行重排序（如果启用）
            List<VectorStoreResult> rerankedResults = fusedResults;
            if (Boolean.TRUE.equals(config.getEnableRerank()) && !fusedResults.isEmpty()) {
                rerankedResults = applyRerankToFusedResults(fusedResults, finalHydeQueryPlan.getRerankQuery());
            }

            return HybridSearchExecutionResult.success(convertToDocumentUnits(rerankedResults, config.getEnableQueryExpansion()));

        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("混合搜索过程中出现错误，查询: '{}', 耗时: {}ms", originalQuery, totalTime, e);
            return HybridSearchExecutionResult.failed(HybridSearchStatus.SEARCH_ERROR, "SEARCH_EXCEPTION", e.getMessage());
        }
    }

    /** 执行混合检索（重载方法，保持向后兼容）
     * @deprecated 推荐使用 hybridSearch(HybridSearchConfig config) 方法 */
    @Deprecated
    public List<DocumentUnitEntity> hybridSearch(List<String> dataSetIds, String question, Integer maxResults,
            Double minScore, Boolean enableRerank, Integer candidateMultiplier,
            EmbeddingModelFactory.EmbeddingConfig embeddingConfig, Boolean enableQueryExpansion) {

        log.warn("使用已废弃的hybridSearch重载方法，建议使用HybridSearchConfig配置对象");

        // 转换为新的配置对象
        HybridSearchConfig config = HybridSearchConfig.builder(dataSetIds, question).maxResults(maxResults)
                .minScore(minScore).enableRerank(enableRerank).candidateMultiplier(candidateMultiplier)
                .embeddingConfig(embeddingConfig).enableQueryExpansion(enableQueryExpansion).build();

        return hybridSearch(config);
    }

    /**
     * 使用RRF算法融合向量检索和关键词检索结果 RRF公式：RRF(d) = Σ(1/(k + rank_i(d)))，其中k=60
     *
     * @param vectorResults  向量检索结果
     * @param keywordResults 关键词检索结果
     * @param maxResults     最大返回结果数量
     * @return 融合后的结果列表
     */
    private List<VectorStoreResult> fusionWithRRF(List<VectorStoreResult> vectorResults,
                                                  List<VectorStoreResult> keywordResults, int maxResults,
                                                  Double minScore) {

        vectorResults = filterFusionCandidates(vectorResults, minScore, "向量");
        keywordResults = filterFusionCandidates(keywordResults, minScore, "关键词");

        log.debug("开始RRF融合 向量: {}, 关键词: {} 结果", vectorResults.size(), keywordResults.size());

        // 存储每个文档的RRF分数
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, VectorStoreResult> documentMap = new HashMap<>();

        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            VectorStoreResult result = vectorResults.get(i);
            if (result == null) {
                log.warn("向量检索结果为空，rank={}", i + 1);
                continue;
            }
            String documentId = result.getDocumentId();

            if (documentId != null && !documentId.trim().isEmpty()) {
                double rrfScore = 1.0 / (Math.max(1, ragProperties.getSearch().getRrfK()) + i + 1); // rank从1开始
                rrfScores.put(documentId, rrfScores.getOrDefault(documentId, 0.0) + rrfScore);

                // 保存文档信息（优先保留向量检索的结果）
                if (!documentMap.containsKey(documentId)) {
                    result.setSearchType(SearchType.HYBRID);
                    documentMap.put(documentId, result);
                } else {
                    mergeConfidence(documentMap.get(documentId), result);
                }

                log.debug("Vector result {}: docId={}, originalScore={}, rrfContribution={}",
                        i + 1, documentId, result.getScore(), rrfScore);
            } else {
                logMissingDocumentId(result, "vector", i + 1);
            }
        }

        // 处理关键词检索结果
        for (int i = 0; i < keywordResults.size(); i++) {
            VectorStoreResult result = keywordResults.get(i);
            if (result == null) {
                log.warn("关键词检索结果为空，rank={}", i + 1);
                continue;
            }
            String documentId = result.getDocumentId();

            if (documentId != null && !documentId.trim().isEmpty()) {
                double rrfScore = 1.0 / (Math.max(1, ragProperties.getSearch().getRrfK()) + i + 1); // rank从1开始
                rrfScores.put(documentId, rrfScores.getOrDefault(documentId, 0.0) + rrfScore);

                // 如果向量检索中没有此文档，保存关键词检索的结果
                if (!documentMap.containsKey(documentId)) {
                    result.setSearchType(SearchType.HYBRID);
                    if (result.getConfidenceTier() == null) {
                        result.setConfidenceTier(ConfidenceTier.HIGH);
                    }
                    documentMap.put(documentId, result);
                } else {
                    mergeConfidence(documentMap.get(documentId), result);
                }

                log.debug("Keyword result {}: docId={}, originalScore={}, rrfContribution={}", i + 1, documentId,
                        result.getScore(), rrfScore);
            } else {
                logMissingDocumentId(result, "keyword", i + 1);
            }
        }

        // 按RRF分数排序并返回
        List<VectorStoreResult> fusedResults = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(entry -> {
                    String documentId = entry.getKey();
                    Double rrfScore = entry.getValue();
                    VectorStoreResult result = documentMap.get(documentId);

                    // 设置融合后的分数
                    result.setScore(rrfScore);

                    log.debug("Fused result: docId={}, finalRRFScore={}", documentId, rrfScore);
                    return result;
                }).collect(Collectors.toList());

        log.info("RRF融合完成: {}个唯一文档，选择前{}个", documentMap.size(), fusedResults.size());

        return fusedResults;
    }

   /**
     * 将VectorStoreResult转换为DocumentUnitEntity 包括查询扩展逻辑
     *
     * @param vectorStoreResults   向量存储检索结果
     * @param enableQueryExpansion 是否启用查询扩展
     * @return DocumentUnitEntity列表
     */
    private List<DocumentUnitEntity> convertToDocumentUnits(List<VectorStoreResult> vectorStoreResults,
                                                            Boolean enableQueryExpansion) {

        if (vectorStoreResults.isEmpty()) {
            return Collections.emptyList();
        }

        // 提取文档ID
        List<String> documentIds = vectorStoreResults.stream().map(VectorStoreResult::getDocumentId)
                .filter(id -> id != null && !id.trim().isEmpty()).collect(Collectors.toList());

        if (documentIds.isEmpty()) {
            log.warn("在向量存储结果中未找到有效的文档ID");
            return Collections.emptyList();
        }

        // 查询文档实体
        List<DocumentUnitEntity> documents = documentUnitRepository.selectList(Wrappers.lambdaQuery(DocumentUnitEntity.class)
                .select(DocumentUnitEntity::getId, DocumentUnitEntity::getFileId, DocumentUnitEntity::getPage,
                        DocumentUnitEntity::getIsVector, DocumentUnitEntity::getIsOcr)
                .in(DocumentUnitEntity::getId, documentIds)
                .eq(DocumentUnitEntity::getIsVector, true));

        if (documents.isEmpty()) {
            log.warn("未找到文档ID对应的DocumentUnitEntity: {}", documentIds);
            return Collections.emptyList();
        }

        List<DocumentUnitEntity> searchableDocuments = filterAccessibleDocuments(documents, "convertToDocumentUnits");
        if (searchableDocuments.isEmpty()) {
            log.warn("文档实体查询成功，但过滤未向量化/已删除文件后无有效文档：{}", documentIds);
            return Collections.emptyList();
        }

        // 按检索结果顺序重建文档列表，确保RRF或rerank后的顺序不会被数据库查询结果顺序打乱
        Map<String, DocumentUnitEntity> documentMap = searchableDocuments.stream()
                .collect(Collectors.toMap(DocumentUnitEntity::getId, doc -> doc, (existing, replacement) -> existing));

        // 创建分数映射
        Map<String, Double> scoreMap = vectorStoreResults.stream().collect(Collectors.toMap(
                VectorStoreResult::getDocumentId, VectorStoreResult::getScore, (existing, replacement) -> existing //
                // 保留第一个值，避免重复key
        ));
        Map<String, ConfidenceTier> confidenceMap = vectorStoreResults.stream().collect(Collectors.toMap(
                VectorStoreResult::getDocumentId, VectorStoreResult::getConfidenceTier,
                ConfidenceTier::max));
        Map<String, String> contentMap = vectorStoreResults.stream()
                .filter(result -> result.getDocumentId() != null)
                .collect(Collectors.toMap(VectorStoreResult::getDocumentId, VectorStoreResult::getText,
                        (existing, replacement) -> existing));

        List<DocumentUnitEntity> orderedDocuments = new ArrayList<>();
        Set<String> addedDocumentIds = new LinkedHashSet<>();

        // 按传入结果顺序组装文档，保留rerank后的排序
        for (VectorStoreResult result : vectorStoreResults) {
            String documentId = result.getDocumentId();
            if (documentId == null || !addedDocumentIds.add(documentId)) {
                continue;
            }

            DocumentUnitEntity doc = documentMap.get(documentId);
            if (doc != null) {
                doc.setContent(contentMap.get(documentId));
                Double score = scoreMap.get(documentId);
                if (score != null) {
                    doc.setSimilarityScore(score);
                }
                doc.setConfidenceTier(confidenceMap.get(documentId));
                orderedDocuments.add(doc);
            }
        }

        if (orderedDocuments.isEmpty()) {
            log.warn("文档实体查询成功，但未能按检索结果顺序匹配到有效文档：{}", documentIds);
            return Collections.emptyList();
        }

        // 查询扩展处理
        if (Boolean.TRUE.equals(enableQueryExpansion)) {
            orderedDocuments = expandQueryResults(orderedDocuments, scoreMap);
        }

        log.debug("转换{}个VectorStoreResult为{}个DocumentUnitEntity，并保留检索结果顺序",
                vectorStoreResults.size(), orderedDocuments.size());

        return orderedDocuments;
    }

    public List<DocumentUnitEntity> rerankDocumentUnits(List<DocumentUnitEntity> documents, String question) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        List<VectorStoreResult> intermediate = new ArrayList<>(documents.size());
        for (DocumentUnitEntity document : documents) {
            if (document == null) {
                continue;
            }
            VectorStoreResult result = new VectorStoreResult();
            result.setText(document.getContent());
            result.setScore(document.getSimilarityScore());
            result.setConfidenceTier(document.getConfidenceTier());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("DOCUMENT_ID", document.getId());
            intermediate.add(result);
            result.setMetadata(metadata);
        }
        List<VectorStoreResult> reranked = applyRerankToFusedResults(intermediate, question);
        Map<String, DocumentUnitEntity> documentMap = documents.stream().filter(Objects::nonNull)
                .filter(document -> document.getId() != null)
                .collect(Collectors.toMap(DocumentUnitEntity::getId, document -> document, (existing, replacement) -> existing,
                        LinkedHashMap::new));
        List<DocumentUnitEntity> ordered = new ArrayList<>();
        Set<String> addedIds = new LinkedHashSet<>();
        for (VectorStoreResult result : reranked) {
            String documentId = result.getDocumentId();
            if (documentId == null || !addedIds.add(documentId)) {
                continue;
            }
            DocumentUnitEntity document = documentMap.get(documentId);
            if (document == null) {
                continue;
            }
            document.setSimilarityScore(result.getScore());
            document.setConfidenceTier(result.getConfidenceTier());
            ordered.add(document);
        }
        return ordered.isEmpty() ? documents : ordered;
    }

/**
     * 查询扩展：添加相邻页面的文档片段
     *
     * @param documents 原始文档列表
     * @param scoreMap  分数映射
     * @return 扩展后的文档列表
     */
    private List<DocumentUnitEntity> expandQueryResults(List<DocumentUnitEntity> documents,
                                                        Map<String, Double> scoreMap) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> expandedIds = new LinkedHashSet<>();
        List<DocumentUnitEntity> expandedDocuments = new ArrayList<>(documents);
        documents.forEach(doc -> expandedIds.add(doc.getId()));

        Map<FilePageKey, ExpansionSeed> pageSeedMap = new LinkedHashMap<>();
        List<DocumentUnitRepository.FilePageRef> refs = new ArrayList<>();
        Set<FilePageKey> scheduledKeys = new LinkedHashSet<>();

        for (int index = 0; index < documents.size(); index++) {
            DocumentUnitEntity doc = documents.get(index);
            if (doc == null || doc.getFileId() == null || doc.getPage() == null) {
                continue;
            }
            double baseScore = scoreMap.get(doc.getId()) != null ? scoreMap.get(doc.getId()) : 0.5;
            double expandedScore = baseScore * 0.8;
            final int sourceOrder = index;
            final DocumentUnitEntity sourceDoc = doc;
            final double candidateExpandedScore = expandedScore;
            for (int page = Math.max(1, doc.getPage() - 1); page <= doc.getPage() + 1; page++) {
                FilePageKey key = new FilePageKey(doc.getFileId(), page);
                pageSeedMap.compute(key, (ignored, existingSeed) -> {
                    if (existingSeed == null) {
                        return new ExpansionSeed(candidateExpandedScore, sourceDoc.getConfidenceTier(), sourceOrder);
                    }
                    existingSeed.merge(candidateExpandedScore, sourceDoc.getConfidenceTier(), sourceOrder);
                    return existingSeed;
                });
                if (scheduledKeys.add(key)) {
                    refs.add(new DocumentUnitRepository.FilePageRef(doc.getFileId(), page));
                }
            }
        }

        if (refs.isEmpty()) {
            return expandedDocuments;
        }

        try {
            List<DocumentUnitEntity> adjacentChunks = filterAccessibleDocuments(
                    documentUnitRepository.selectAdjacentChunks(refs), "expandQueryResults");
            adjacentChunks.sort(Comparator
                    .comparingInt((DocumentUnitEntity chunk) -> pageSeedMap
                            .getOrDefault(new FilePageKey(chunk.getFileId(), chunk.getPage()),
                                    ExpansionSeed.defaultSeed())
                            .getFirstSourceOrder())
                    .thenComparing(DocumentUnitEntity::getFileId, Comparator.nullsLast(String::compareTo))
                    .thenComparing(DocumentUnitEntity::getPage, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(DocumentUnitEntity::getId, Comparator.nullsLast(String::compareTo)));

            for (DocumentUnitEntity chunk : adjacentChunks) {
                if (chunk == null || chunk.getId() == null || expandedIds.contains(chunk.getId())) {
                    continue;
                }
                ExpansionSeed seed = pageSeedMap.get(new FilePageKey(chunk.getFileId(), chunk.getPage()));
                if (seed == null) {
                    continue;
                }
                chunk.setSimilarityScore(seed.getBestExpandedScore());
                chunk.setConfidenceTier(seed.getConfidenceTier());
                expandedDocuments.add(chunk);
                expandedIds.add(chunk.getId());
            }
        } catch (Exception e) {
            log.warn("批量扩展查询失败，documents={}", documents.size(), e);
        }

        log.info("查询扩展: {}个原始文档扩展为{}个总文档", documents.size(), expandedDocuments.size());
        return expandedDocuments;
    }

    private List<DocumentUnitEntity> filterAccessibleDocuments(List<DocumentUnitEntity> documents, String source) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<DocumentUnitEntity> vectorizedDocuments = documents.stream()
                .filter(Objects::nonNull)
                .filter(doc -> Boolean.TRUE.equals(doc.getIsVector()))
                .toList();

        int filteredNonVectorCount = documents.size() - vectorizedDocuments.size();
        if (filteredNonVectorCount > 0) {
            log.warn("{} 过滤掉{}个未向量化文档", source, filteredNonVectorCount);
        }

        Set<String> activeFileIds = loadActiveFileIds(vectorizedDocuments);
        List<DocumentUnitEntity> accessibleDocuments = vectorizedDocuments.stream()
                .filter(doc -> doc.getFileId() != null && activeFileIds.contains(doc.getFileId()))
                .toList();

        int filteredDeletedFileCount = vectorizedDocuments.size() - accessibleDocuments.size();
        if (filteredDeletedFileCount > 0) {
            log.warn("{} 过滤掉{}个所属文件已删除或无效的文档", source, filteredDeletedFileCount);
        }

        return accessibleDocuments;
    }

    private Set<String> loadActiveFileIds(List<DocumentUnitEntity> documents) {
        List<String> fileIds = documents.stream().map(DocumentUnitEntity::getFileId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fileIds.isEmpty()) {
            return Collections.emptySet();
        }

        return fileDetailRepository.selectBatchIds(fileIds).stream()
                .map(FileDetailEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 对RRF融合后的结果进行重排序
     *
     * @param fusedResults RRF融合后的结果
     * @param question     查询问题
     * @return 重排序后的结果
     */
    private List<VectorStoreResult> applyRerankToFusedResults(List<VectorStoreResult> fusedResults, String question) {
        if (fusedResults.isEmpty()) {
            return fusedResults;
        }

        long rerankStartTime = System.currentTimeMillis();

        try {
            // 提取文档文本列表
            List<String> texts = fusedResults.stream().map(VectorStoreResult::getText).collect(Collectors.toList());

            // 调用重排序服务获取重排序后的索引和分数
            List<RerankDomainService.RerankResult> rerankResults = rerankDomainService.rerankWithScores(texts, question);

            // 根据重排序索引重新排列结果，并回写rerank分数
            List<VectorStoreResult> rerankedResults = new ArrayList<>();
            Set<Integer> addedIndices = new LinkedHashSet<>();

            for (RerankDomainService.RerankResult rerankResult : rerankResults) {
                Integer index = rerankResult.index();
                if (index == null || index < 0 || index >= fusedResults.size() || !addedIndices.add(index)) {
                    continue;
                }
                VectorStoreResult result = fusedResults.get(index);
                if (rerankResult.relevanceScore() != null) {
                    result.setScore(rerankResult.relevanceScore());
                }
                rerankedResults.add(result);
            }

            for (int index = 0; index < fusedResults.size(); index++) {
                if (addedIndices.add(index)) {
                    rerankedResults.add(fusedResults.get(index));
                }
            }

            long rerankTime = System.currentTimeMillis() - rerankStartTime;
            log.info("对查询'{}'的融合结果应用重排序，{}个结果，耗时{}ms", question, rerankedResults.size(), rerankTime);

            return rerankedResults;
        } catch (Exception e) {
            long rerankTime = System.currentTimeMillis() - rerankStartTime;
            log.error("对查询'{}'的融合结果重排序失败，耗时{}ms", question, rerankTime, e);
            // 重排序失败时返回原始融合结果
            return fusedResults;
        }
    }

    private void mergeConfidence(VectorStoreResult existing, VectorStoreResult candidate) {
        existing.setConfidenceTier(ConfidenceTier.max(existing.getConfidenceTier(), candidate.getConfidenceTier()));
        if (existing.getMatchedMinScore() == null) {
            existing.setMatchedMinScore(candidate.getMatchedMinScore());
        }
    }

    private int normalizeTimeoutSeconds(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return DEFAULT_SEARCH_TIMEOUT_SECONDS;
        }
        return Math.max(1, timeoutSeconds);
    }

    private void waitForSearchChannels(CompletableFuture<List<VectorStoreResult>> vectorSearchFuture,
            CompletableFuture<List<VectorStoreResult>> keywordSearchFuture, String originalQuery, int timeoutSeconds) {
        try {
            CompletableFuture.allOf(vectorSearchFuture, keywordSearchFuture).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            vectorSearchFuture.cancel(true);
            keywordSearchFuture.cancel(true);
            log.warn("混合搜索总超时，query='{}', timeoutSeconds={}", originalQuery, timeoutSeconds);
        } catch (Exception e) {
            log.warn("混合搜索并行通道存在异常，query='{}': {}", originalQuery, e.getMessage());
        }
    }

    private SearchChannelResult collectSearchChannelResult(CompletableFuture<List<VectorStoreResult>> future,
            String searchType, String originalQuery) {
        if (future.isCancelled()) {
            return SearchChannelResult.failed("TIMEOUT", searchType + "搜索超时");
        }
        if (!future.isDone()) {
            future.cancel(true);
            return SearchChannelResult.failed("NOT_COMPLETED", searchType + "搜索未完成");
        }
        try {
            List<VectorStoreResult> results = future.getNow(Collections.emptyList());
            log.debug("{}搜索完成，找到{}个结果", searchType, results.size());
            return SearchChannelResult.success(results);
        } catch (Exception e) {
            log.warn("{}搜索失败，query='{}': {}", searchType, originalQuery, e.getMessage());
            return SearchChannelResult.failed("CHANNEL_EXCEPTION", searchType + "搜索失败: " + e.getMessage());
        }
    }

    private SearchChannelResult awaitSearchResultsWithinBudget(CompletableFuture<List<VectorStoreResult>> future,
            String searchType, String originalQuery, int timeoutSeconds, long deadlineNanos) {
        try {
            if (future.isDone()) {
                List<VectorStoreResult> results = future.getNow(Collections.emptyList());
                log.debug("{}搜索完成，找到{}个结果", searchType, results.size());
                return SearchChannelResult.success(results);
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                future.cancel(true);
                log.warn("{}搜索超时预算已耗尽，query='{}', timeoutSeconds={}", searchType, originalQuery,
                        timeoutSeconds);
                return SearchChannelResult.failed("TIMEOUT_BUDGET_EXHAUSTED", searchType + "搜索超时预算已耗尽");
            }

            long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            List<VectorStoreResult> results = future.get(waitMillis, TimeUnit.MILLISECONDS);
            log.debug("{}搜索完成，找到{}个结果", searchType, results.size());
            return SearchChannelResult.success(results);
        } catch (TimeoutException e) {
            future.cancel(true);
            long remainingMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            log.warn("{}搜索超时，query='{}', timeoutSeconds={}, remainingMillis={}", searchType, originalQuery,
                    timeoutSeconds, remainingMillis);
            return SearchChannelResult.failed("TIMEOUT", searchType + "搜索超时");
        } catch (Exception e) {
            log.warn("{}搜索失败，query='{}': {}", searchType, originalQuery, e.getMessage());
            return SearchChannelResult.failed("CHANNEL_EXCEPTION", searchType + "搜索失败: " + e.getMessage());
        }
    }

    private String mergeErrorMessages(SearchChannelResult vectorChannelResult, SearchChannelResult keywordChannelResult) {
        List<String> errors = new ArrayList<>(2);
        if (vectorChannelResult != null && vectorChannelResult.getErrorMessage() != null) {
            errors.add(vectorChannelResult.getErrorMessage());
        }
        if (keywordChannelResult != null && keywordChannelResult.getErrorMessage() != null) {
            errors.add(keywordChannelResult.getErrorMessage());
        }
        return String.join("; ", errors);
    }

    private List<VectorStoreResult> filterFusionCandidates(List<VectorStoreResult> results, Double minScore,
            String channelName) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        double scoreFloor = Math.max(0.0, Math.min(minScore == null ? 0.0 : minScore * 0.5, 1.0));
        List<VectorStoreResult> filtered = results.stream()
                .filter(Objects::nonNull)
                .filter(result -> result.getConfidenceTier() != ConfidenceTier.FALLBACK
                        || (result.getScore() != null && result.getScore() >= scoreFloor))
                .toList();
        if (filtered.isEmpty()) {
            log.debug("{}检索候选经score/tier过滤后为空，保留原始结果兜底", channelName);
            return results.stream().filter(Objects::nonNull).toList();
        }
        int dropped = results.size() - filtered.size();
        if (dropped > 0) {
            log.debug("{}检索RRF前过滤低置信候选{}条，保留{}条", channelName, dropped, filtered.size());
        }
        return filtered;
    }

    private void logMissingDocumentId(VectorStoreResult result, String sourceType, int rank) {
        log.warn("RRF丢弃无documentId结果: source={}, rank={}, embeddingId={}, fileId={}, dataSetId={}, score={}",
                sourceType, rank, result.getEmbeddingId(), result.getFileId(), result.getDataSetId(), result.getScore());
    }

    private record FilePageKey(String fileId, Integer page) {
    }

    private static final class ExpansionSeed {
        private double bestExpandedScore;
        private ConfidenceTier confidenceTier;
        private int firstSourceOrder;

        private ExpansionSeed(double bestExpandedScore, ConfidenceTier confidenceTier, int firstSourceOrder) {
            this.bestExpandedScore = bestExpandedScore;
            this.confidenceTier = confidenceTier;
            this.firstSourceOrder = firstSourceOrder;
        }

        private static ExpansionSeed defaultSeed() {
            return new ExpansionSeed(0.0, null, Integer.MAX_VALUE);
        }

        private void merge(double candidateScore, ConfidenceTier candidateTier, int candidateOrder) {
            if (candidateScore > this.bestExpandedScore) {
                this.bestExpandedScore = candidateScore;
            }
            this.confidenceTier = ConfidenceTier.max(this.confidenceTier, candidateTier);
            this.firstSourceOrder = Math.min(this.firstSourceOrder, candidateOrder);
        }

        private double getBestExpandedScore() {
            return bestExpandedScore;
        }

        private ConfidenceTier getConfidenceTier() {
            return confidenceTier;
        }

        private int getFirstSourceOrder() {
            return firstSourceOrder;
        }
    }

    public enum HybridSearchStatus {
        SUCCESS,
        NO_RESULTS,
        INVALID_CONFIG,
        SEARCH_ERROR
    }

    public static final class HybridSearchExecutionResult {
        private final HybridSearchStatus status;
        private final List<DocumentUnitEntity> documents;
        private final String errorCode;
        private final String errorMessage;

        private HybridSearchExecutionResult(HybridSearchStatus status, List<DocumentUnitEntity> documents, String errorCode,
                String errorMessage) {
            this.status = status;
            this.documents = documents == null ? Collections.emptyList() : documents;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static HybridSearchExecutionResult success(List<DocumentUnitEntity> documents) {
            if (documents == null || documents.isEmpty()) {
                return noResults();
            }
            return new HybridSearchExecutionResult(HybridSearchStatus.SUCCESS, documents, null, null);
        }

        public static HybridSearchExecutionResult noResults() {
            return new HybridSearchExecutionResult(HybridSearchStatus.NO_RESULTS, Collections.emptyList(), null, null);
        }

        public static HybridSearchExecutionResult failed(HybridSearchStatus status, String errorCode, String errorMessage) {
            return new HybridSearchExecutionResult(status, Collections.emptyList(), errorCode, errorMessage);
        }

        public HybridSearchStatus getStatus() {
            return status;
        }

        public List<DocumentUnitEntity> getDocuments() {
            return documents;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public boolean isSuccess() {
            return status == HybridSearchStatus.SUCCESS;
        }

        public boolean isFailure() {
            return status == HybridSearchStatus.INVALID_CONFIG || status == HybridSearchStatus.SEARCH_ERROR;
        }
    }

    private static final class SearchChannelResult {
        private final List<VectorStoreResult> results;
        private final boolean success;
        private final String errorCode;
        private final String errorMessage;

        private SearchChannelResult(List<VectorStoreResult> results, boolean success, String errorCode, String errorMessage) {
            this.results = results == null ? Collections.emptyList() : results;
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        private static SearchChannelResult success(List<VectorStoreResult> results) {
            return new SearchChannelResult(results, true, null, null);
        }

        private static SearchChannelResult failed(String errorCode, String errorMessage) {
            return new SearchChannelResult(Collections.emptyList(), false, errorCode, errorMessage);
        }

        private List<VectorStoreResult> getResults() {
            return results;
        }

        private boolean isSuccess() {
            return success;
        }

        private String getErrorCode() {
            return errorCode;
        }

        private String getErrorMessage() {
            return errorMessage;
        }
    }
}
