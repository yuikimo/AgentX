package com.example.agentx.application.rag.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskExecutor;
import com.example.agentx.application.conversation.service.message.rag.RagFailedGroupInfo;
import com.example.agentx.application.rag.assembler.DocumentUnitAssembler;
import com.example.agentx.application.rag.dto.*;
import com.example.agentx.application.rag.service.manager.RagQaDatasetAppService;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.rag.service.DocumentUnitDomainService;
import com.example.agentx.domain.rag.service.EmbeddingDomainService;
import com.example.agentx.domain.rag.service.FileDetailDomainService;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.service.RagQaDatasetDomainService;
import com.example.agentx.domain.rag.service.management.RagDataAccessDomainService;
import com.example.agentx.domain.rag.service.management.UserRagDomainService;
import com.example.agentx.domain.rag.service.management.UserRagFileDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.LLMServiceFactory;
import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;
import com.example.agentx.infrastructure.rag.service.DatasetEmbeddingConfigResolver;

import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.model.RagQaDatasetEntity;
import com.example.agentx.domain.rag.repository.DocumentUnitRepository;
import com.example.agentx.domain.rag.repository.FileDetailRepository;
import com.example.agentx.domain.rag.dto.HybridSearchConfig;
import com.example.agentx.domain.rag.constant.ConfidenceTier;
import com.example.agentx.domain.rag.service.*;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RAGSearchAppService {

    private static final Logger log = LoggerFactory.getLogger(RagQaDatasetAppService.class);
    private static final Cache<String, ModelConfig> USER_CHAT_MODEL_CONFIG_CACHE = CacheBuilder.newBuilder()
            .maximumSize(4096).expireAfterWrite(Duration.ofMinutes(5)).recordStats().build();
    private static final Cache<String, List<CachedDocumentUnitDTO>> FINAL_RAG_RESULT_CACHE = CacheBuilder.newBuilder()
            .maximumSize(4096).expireAfterWrite(10, TimeUnit.MINUTES).recordStats().build();
    private static final Cache<String, List<CachedDocumentUnitEntity>> RAW_RAG_RESULT_CACHE = CacheBuilder.newBuilder()
            .maximumSize(2048).expireAfterWrite(10, TimeUnit.MINUTES).recordStats().build();
    private static final Cache<String, List<RagQaDatasetEntity>> ACCESSIBLE_DATASET_CACHE = CacheBuilder.newBuilder()
            .maximumSize(2048).expireAfterWrite(2, TimeUnit.MINUTES).recordStats().build();
    private static final Cache<String, String> FILE_VERSION_FINGERPRINT_CACHE = CacheBuilder.newBuilder()
            .maximumSize(4096).expireAfterWrite(2, TimeUnit.MINUTES).recordStats().build();
    private static final AtomicLong GROUP_SEARCH_FAILURE_COUNTER = new AtomicLong();
    private static final AtomicLong GROUP_SEARCH_MAJORITY_FAILURE_COUNTER = new AtomicLong();

    private final RagQaDatasetDomainService ragQaDatasetDomainService;
    private final FileDetailDomainService fileDetailDomainService;
    private final DocumentUnitDomainService documentUnitDomainService;
    private final EmbeddingDomainService embeddingDomainService;
    private final ObjectMapper objectMapper;
    private final LLMServiceFactory llmServiceFactory;
    private final LLMDomainService llmDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final HighAvailabilityDomainService highAvailabilityDomainService;

    private final UserRagDomainService userRagDomainService;
    private final RagDataAccessDomainService ragDataAccessService;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final HybridSearchDomainService hybridSearchDomainService;
    private final HyDEDomainService hydeDomainService;
    private final UserModelConfigResolver userModelConfigResolver;
    private final UserRagFileDomainService userRagFileDomainService;
    private final DatasetEmbeddingConfigResolver datasetEmbeddingConfigResolver;
    private final TaskExecutor ragSearchGroupTaskExecutor;
    private final RagProperties ragProperties;

    public RAGSearchAppService(RagQaDatasetDomainService ragQaDatasetDomainService,
            FileDetailDomainService fileDetailDomainService, DocumentUnitDomainService documentUnitDomainService,
            EmbeddingDomainService embeddingDomainService, ObjectMapper objectMapper,
            LLMServiceFactory llmServiceFactory, LLMDomainService llmDomainService,
            UserSettingsDomainService userSettingsDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService, UserRagDomainService userRagDomainService,
            RagDataAccessDomainService ragDataAccessService, EmbeddingModelFactory embeddingModelFactory,
            HybridSearchDomainService hybridSearchDomainService,
            HyDEDomainService hydeDomainService,
            com.example.agentx.infrastructure.rag.service.UserModelConfigResolver userModelConfigResolver,
            UserRagFileDomainService userRagFileDomainService,
            DatasetEmbeddingConfigResolver datasetEmbeddingConfigResolver,
            @Qualifier("ragSearchGroupTaskExecutor") TaskExecutor ragSearchGroupTaskExecutor,
            RagProperties ragProperties) {
        this.ragQaDatasetDomainService = ragQaDatasetDomainService;
        this.fileDetailDomainService = fileDetailDomainService;
        this.documentUnitDomainService = documentUnitDomainService;
        this.embeddingDomainService = embeddingDomainService;
        this.objectMapper = objectMapper;
        this.llmServiceFactory = llmServiceFactory;
        this.llmDomainService = llmDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.highAvailabilityDomainService = highAvailabilityDomainService;
        this.userRagDomainService = userRagDomainService;
        this.ragDataAccessService = ragDataAccessService;
        this.embeddingModelFactory = embeddingModelFactory;
        this.hybridSearchDomainService = hybridSearchDomainService;
        this.hydeDomainService = hydeDomainService;
        this.userModelConfigResolver = userModelConfigResolver;
        this.userRagFileDomainService = userRagFileDomainService;
        this.datasetEmbeddingConfigResolver = datasetEmbeddingConfigResolver;
        this.ragSearchGroupTaskExecutor = ragSearchGroupTaskExecutor;
        this.ragProperties = ragProperties;
    }

    /** RAG搜索文档（使用智能参数优化）
     * @param request 搜索请求
     * @param userId 用户ID
     * @return 搜索结果 */
    public List<DocumentUnitDTO> ragSearch(RagSearchRequest request, String userId) {
        return ragSearchDetailed(request, userId).getDocuments();
    }

    public SearchExecutionSummary ragSearchDetailed(RagSearchRequest request, String userId) {
        return ragSearchDetailed(request, userId, SearchProgressListener.noop());
    }

    public SearchExecutionSummary ragSearchDetailed(RagSearchRequest request, String userId,
            SearchProgressListener progressListener) {
        List<RagQaDatasetEntity> accessibleDatasets = resolveAccessibleDatasets(userId, request.getDatasetIds());
        if (accessibleDatasets.isEmpty()) {
            log.warn("用户 {} 没有任何有效的知识库可搜索", userId);
            return SearchExecutionSummary.empty();
        }

        // 获取用户的聊天模型配置用于HyDE
        ModelConfig chatModelConfig = null;
        try {
            chatModelConfig = resolveCachedUserChatModelConfig(userId);
            if (chatModelConfig != null) {
                log.debug("获取用户 {} 的聊天模型配置成功，modelId: {}", userId, chatModelConfig.getModelEndpoint());
            }
        } catch (Exception e) {
            log.warn("获取用户 {} 的聊天模型配置失败，HyDE功能将不可用: {}", userId, e.getMessage());
        }

        String rawCacheKey = buildRawRetrievalCacheKey("dataset", userId, null, accessibleDatasets, request,
                chatModelConfig);
        String finalCacheKey = buildFinalRetrievalCacheKey(rawCacheKey, request);
        List<DocumentUnitDTO> cached = getCachedFinalResult(finalCacheKey);
        if (cached != null) {
            log.debug("RAG检索结果缓存命中: userId={}, datasets={}", userId,
                    accessibleDatasets.stream().map(RagQaDatasetEntity::getId).toList());
            return SearchExecutionSummary.success(cached);
        }

        // 使用智能调整后的参数进行混合检索
        Double adjustedMinScore = request.getAdjustedMinScore();
        Integer adjustedCandidateMultiplier = request.getAdjustedCandidateMultiplier();

        List<DocumentUnitEntity> rawEntities = getCachedRawResult(rawCacheKey);
        List<RagFailedGroupInfo> failedGroups = Collections.emptyList();
        if (rawEntities == null) {
            GroupedSearchSummary groupedSearchSummary = executeHybridSearchByDatasetGroups(accessibleDatasets, request, adjustedMinScore,
                    adjustedCandidateMultiplier, chatModelConfig, resolveRawCandidateLimit(request), progressListener);
            rawEntities = groupedSearchSummary.documents();
            failedGroups = groupedSearchSummary.failedGroups();
            cacheRawResult(rawCacheKey, rawEntities);
        }

        List<DocumentUnitEntity> entities = finalizeResults(rawEntities, request.getEnableRerank(), request.getQuestion(),
                request.getMaxResults(), progressListener);

        // 转换为DTO并返回
        List<DocumentUnitDTO> results = DocumentUnitAssembler.toDTOs(entities);
        cacheFinalResult(finalCacheKey, results);
        return new SearchExecutionSummary(results, failedGroups);
    }

    /** 基于已安装知识库的RAG搜索
     *
     * @param request RAG搜索请求（使用userRagId作为数据源）
     * @param userRagId 用户已安装的RAG ID
     * @param userId 用户ID
     * @return 搜索结果 */
    public List<DocumentUnitDTO> ragSearchByUserRag(RagSearchRequest request, String userRagId, String userId) {
        return ragSearchByUserRagDetailed(request, userRagId, userId).getDocuments();
    }

    public SearchExecutionSummary ragSearchByUserRagDetailed(RagSearchRequest request, String userRagId, String userId) {
        return ragSearchByUserRagDetailed(request, userRagId, userId, SearchProgressListener.noop());
    }

    public SearchExecutionSummary ragSearchByUserRagDetailed(RagSearchRequest request, String userRagId, String userId,
            SearchProgressListener progressListener) {
        // 获取RAG数据源信息
        RagDataAccessDomainService.RagDataSourceInfo sourceInfo = ragDataAccessService.getRagDataSourceInfo(userId,
                userRagId);

        // 根据安装类型获取实际的数据集ID
        String actualDatasetId;
        if (sourceInfo.getIsRealTime()) {
            // REFERENCE类型：使用原始数据集ID
            actualDatasetId = sourceInfo.getOriginalRagId();
        } else {
            // SNAPSHOT类型：使用原始数据集ID（但实际搜索会通过版本控制过滤）
            actualDatasetId = sourceInfo.getOriginalRagId();
        }

        // 验证数据集权限 - 通过userRagId已经验证了权限，不需要再检查用户是否是创建者
        // 只需要确认原始数据集仍然存在
        var originalDataset = ragQaDatasetDomainService.findDatasetById(actualDatasetId);
        if (originalDataset == null) {
            throw new BusinessException("原始数据集不存在或已被删除");
        }

        // 获取用户的聊天模型配置用于HyDE
        ModelConfig chatModelConfig = null;
        try {
            chatModelConfig = resolveCachedUserChatModelConfig(userId);
            if (chatModelConfig != null) {
                log.debug("ragSearchByUserRag - 获取用户 {} 的聊天模型配置成功，modelId: {}", userId,
                        chatModelConfig.getModelEndpoint());
            }
        } catch (Exception e) {
            log.warn("ragSearchByUserRag - 获取用户 {} 的聊天模型配置失败，HyDE功能将不可用: {}", userId, e.getMessage());
        }

        String rawCacheKey = buildRawRetrievalCacheKey("user-rag", userId, userRagId, List.of(originalDataset), request,
                chatModelConfig);
        String finalCacheKey = buildFinalRetrievalCacheKey(rawCacheKey, request);
        List<DocumentUnitDTO> cached = getCachedFinalResult(finalCacheKey);
        if (cached != null) {
            log.debug("UserRAG检索结果缓存命中: userId={}, userRagId={}", userId, userRagId);
            return SearchExecutionSummary.success(cached);
        }

        // 使用智能调整后的参数进行RAG搜索
        Double adjustedMinScore = request.getAdjustedMinScore();
        Integer adjustedCandidateMultiplier = request.getAdjustedCandidateMultiplier();

        List<DocumentUnitEntity> rawEntities = getCachedRawResult(rawCacheKey);
        List<RagFailedGroupInfo> failedGroups = Collections.emptyList();
        if (rawEntities == null) {
            GroupedSearchSummary groupedSearchSummary = executeHybridSearchByDatasetGroups(List.of(originalDataset), request, adjustedMinScore,
                    adjustedCandidateMultiplier, chatModelConfig, resolveRawCandidateLimit(request), progressListener);
            rawEntities = groupedSearchSummary.documents();
            failedGroups = groupedSearchSummary.failedGroups();
            cacheRawResult(rawCacheKey, rawEntities);
        }

        List<DocumentUnitEntity> entities = finalizeResults(rawEntities, request.getEnableRerank(), request.getQuestion(),
                request.getMaxResults(), progressListener);

        // 转换为DTO并返回
        List<DocumentUnitDTO> results = DocumentUnitAssembler.toDTOs(entities);
        cacheFinalResult(finalCacheKey, results);
        return new SearchExecutionSummary(results, failedGroups);
    }

    public void invalidateAccessibleDatasetCache(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String prefix = userId + "|";
        List<String> keys = ACCESSIBLE_DATASET_CACHE.asMap().keySet().stream()
                .filter(key -> key != null && key.startsWith(prefix)).toList();
        if (!keys.isEmpty()) {
            ACCESSIBLE_DATASET_CACHE.invalidateAll(keys);
            log.debug("已失效用户可访问知识库缓存: userId={}, keyCount={}", userId, keys.size());
        }
    }

    public void invalidateUserChatModelConfigCache(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        USER_CHAT_MODEL_CONFIG_CACHE.invalidate(userId);
        log.debug("已失效用户聊天模型配置缓存: userId={}", userId);
    }

    public CompletableFuture<HyDEDomainService.HyDEQueryPlan> prepareHydeQueryPlanAsync(String userId, String question) {
        return CompletableFuture.supplyAsync(() -> prepareHydeQueryPlan(userId, question), ragSearchGroupTaskExecutor);
    }

    public HyDEDomainService.HyDEQueryPlan prepareHydeQueryPlan(String userId, String question) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) {
            return HyDEDomainService.HyDEQueryPlan.skip("", "empty_query", "empty");
        }
        try {
            ModelConfig chatModelConfig = resolveCachedUserChatModelConfig(userId);
            if (chatModelConfig == null) {
                return HyDEDomainService.HyDEQueryPlan.skip(normalizedQuestion, "missing_chat_model_config", "missing");
            }
            return hydeDomainService.prepareQueryPlan(normalizedQuestion, chatModelConfig);
        } catch (Exception e) {
            log.warn("预热HyDE查询计划失败: userId={}, err={}", userId, e.getMessage());
            return HyDEDomainService.HyDEQueryPlan.failed(normalizedQuestion, "prepare_failed",
                    e.getMessage() == null ? "prepare_failed" : e.getMessage());
        }
    }

    /** 将ModelConfig转换为EmbeddingModelFactory.EmbeddingConfig
     *
     * @param modelConfig RAG模型配置
     * @return 嵌入模型工厂配置 */
    private EmbeddingModelFactory.EmbeddingConfig toEmbeddingConfig(ModelConfig modelConfig) {
        return new EmbeddingModelFactory.EmbeddingConfig(modelConfig.getApiKey(), modelConfig.getBaseUrl(),
                modelConfig.getModelEndpoint());
    }

    private GroupedSearchSummary executeHybridSearchByDatasetGroups(List<RagQaDatasetEntity> datasets,
            RagSearchRequest request, Double adjustedMinScore, Integer adjustedCandidateMultiplier,
            ModelConfig chatModelConfig, int rawCandidateLimit, SearchProgressListener progressListener) {
        List<DatasetSearchGroup> groups = buildDatasetSearchGroups(datasets);
        if (groups.isEmpty()) {
            return GroupedSearchSummary.empty();
        }
        SearchProgressListener safeProgressListener = progressListener == null ? SearchProgressListener.noop()
                : progressListener;

        HyDEDomainService.HyDEQueryPlan precomputedHydeQueryPlan = request.getPrecomputedHydeQueryPlan();
        HyDEDomainService.HyDEQueryPlan hydeQueryPlan = Boolean.FALSE.equals(request.getEnableHyde())
                ? HyDEDomainService.HyDEQueryPlan.skip(request.getQuestion(), "disabled_by_request", "disabled")
                : precomputedHydeQueryPlan != null
                        ? precomputedHydeQueryPlan
                        : hydeDomainService.prepareQueryPlan(request.getQuestion(), chatModelConfig);
        log.info("分组检索复用HyDE计划: queryHash={}, applied={}, cacheHit={}, groups={}", hydeQueryPlan.getQueryHash(),
                hydeQueryPlan.isHydeApplied(), hydeQueryPlan.isCacheHit(), groups.size());
        safeProgressListener.onHydePrepared(hydeQueryPlan, groups.size());

        AtomicInteger completedGroups = new AtomicInteger();
        List<GroupSearchFuture> futures = groups.stream()
                .map(group -> {
                    CompletableFuture<GroupSearchExecutionResult> future = CompletableFuture.supplyAsync(() -> {
                        HybridSearchConfig config = HybridSearchConfig.builder(group.datasetIds(), request.getQuestion())
                                .maxResults(rawCandidateLimit)
                                .minScore(adjustedMinScore)
                                .fallbackMinScore(request.getFallbackMinScore())
                                .enableRerank(Boolean.FALSE)
                                .candidateMultiplier(adjustedCandidateMultiplier)
                                .timeoutSeconds(request.getTimeoutSeconds())
                                .embeddingConfig(group.embeddingConfig())
                                .vectorTableName(group.vectorTableName())
                                .vectorDimension(group.vectorDimension())
                                .enableQueryExpansion(request.getEnableQueryExpansion())
                                .chatModelConfig(chatModelConfig)
                                .hydeQueryPlan(hydeQueryPlan)
                                .build();
                        HybridSearchDomainService.HybridSearchExecutionResult result = hybridSearchDomainService
                                .hybridSearchWithStatus(config);
                        return GroupSearchExecutionResult.of(group.datasetIds(), result);
                    }, ragSearchGroupTaskExecutor).handle((result, throwable) -> {
                        if (throwable == null) {
                            return result;
                        }
                        long failureCount = GROUP_SEARCH_FAILURE_COUNTER.incrementAndGet();
                        String errorMessage = resolveThrowableMessage(throwable);
                        String errorCode = throwable instanceof CancellationException ? "GROUP_SEARCH_TIMEOUT"
                                : "GROUP_EXECUTION_EXCEPTION";
                        log.warn("分组检索执行异常: datasets={}, error={}, failureCount={}", group.datasetIds(), errorMessage,
                                failureCount);
                        return GroupSearchExecutionResult.failed(group.datasetIds(), errorCode, errorMessage);
                    }).whenComplete((result, throwable) -> notifyGroupProgress(safeProgressListener,
                            completedGroups.incrementAndGet(), groups.size(), group.datasetIds(), result));
                    return new GroupSearchFuture(group, future);
                }).toList();

        awaitGroupSearches(futures, request);
        List<GroupSearchExecutionResult> groupResults = futures.stream().map(this::resolveGroupSearchResult)
                .filter(Objects::nonNull).toList();
        long failureGroups = groupResults.stream().filter(GroupSearchExecutionResult::isFailure).count();
        long successGroups = groupResults.stream().filter(GroupSearchExecutionResult::isSuccess).count();
        long noResultGroups = groupResults.stream().filter(GroupSearchExecutionResult::isNoResults).count();
        List<RagFailedGroupInfo> failedGroups = groupResults.stream().filter(GroupSearchExecutionResult::isFailure)
                .map(result -> new RagFailedGroupInfo(result.datasetIds(), result.errorCode(), result.errorMessage()))
                .toList();
        if (failureGroups > 0) {
            long failureCount = GROUP_SEARCH_FAILURE_COUNTER.addAndGet(failureGroups);
            log.warn("分组检索存在失败: groups={}, successGroups={}, noResultGroups={}, failureGroups={}, failureCount={}",
                    groups.size(), successGroups, noResultGroups, failureGroups, failureCount);
        }
        if (failureGroups > 0 && failureGroups > groups.size() / 2) {
            long majorityFailureCount = GROUP_SEARCH_MAJORITY_FAILURE_COUNTER.incrementAndGet();
            throw new BusinessException("知识库检索失败，请稍后重试。失败分组数: " + failureGroups + "/" + groups.size()
                    + "，majorityFailureCount=" + majorityFailureCount);
        }

        List<List<DocumentUnitEntity>> groupedResults = groupResults.stream().filter(GroupSearchExecutionResult::hasDocuments)
                .map(GroupSearchExecutionResult::documents).toList();
        if (groupedResults.isEmpty()) {
            return new GroupedSearchSummary(new ArrayList<>(), failedGroups);
        }
        if (groupedResults.size() == 1) {
            return new GroupedSearchSummary(limitResults(groupedResults.get(0), rawCandidateLimit), failedGroups);
        }
        return new GroupedSearchSummary(mergeGroupedResults(groupedResults, rawCandidateLimit), failedGroups);
    }

    private List<DatasetSearchGroup> buildDatasetSearchGroups(List<RagQaDatasetEntity> datasets) {
        Map<String, DatasetSearchGroup> groupMap = new LinkedHashMap<>();
        for (RagQaDatasetEntity dataset : datasets) {
            if (dataset == null || dataset.getId() == null) {
                log.warn("知识库为空，跳过检索分组");
                continue;
            }
            try {
                var context = datasetEmbeddingConfigResolver.resolveByDatasetEntity(dataset, dataset.getUserId());
                String profileId = context.profileId();
                DatasetSearchGroup group = groupMap.get(profileId);
                if (group == null) {
                    group = new DatasetSearchGroup(new ArrayList<>(), toEmbeddingConfig(context.modelConfig()),
                            context.tableName(), context.dimension());
                    groupMap.put(profileId, group);
                }
                group.datasetIds().add(dataset.getId());
            } catch (Exception e) {
                log.warn("构建知识库检索分组失败，datasetId={}, error={}", dataset.getId(), e.getMessage());
            }
        }
        return new ArrayList<>(groupMap.values());
    }

    private List<RagQaDatasetEntity> resolveAccessibleDatasets(String userId, List<String> requestedDatasetIds) {
        if (requestedDatasetIds == null || requestedDatasetIds.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> orderedDistinctIds = requestedDatasetIds.stream().filter(Objects::nonNull)
                .map(String::trim).filter(id -> !id.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderedDistinctIds.isEmpty()) {
            return Collections.emptyList();
        }
        String cacheKey = userId + "|" + sha256(String.join(",", orderedDistinctIds));
        List<RagQaDatasetEntity> cached = ACCESSIBLE_DATASET_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        Map<String, RagQaDatasetEntity> datasetMap = ragQaDatasetDomainService.findDatasetsByIds(
                new ArrayList<>(orderedDistinctIds)).stream().filter(Objects::nonNull)
                        .collect(Collectors.toMap(RagQaDatasetEntity::getId, dataset -> dataset,
                                (existing, replacement) -> existing, LinkedHashMap::new));
        Set<String> installedDatasetIds = userRagDomainService.findInstalledOriginalRagIds(userId,
                new ArrayList<>(orderedDistinctIds));

        List<RagQaDatasetEntity> accessibleDatasets = new ArrayList<>();
        for (String datasetId : orderedDistinctIds) {
            RagQaDatasetEntity dataset = datasetMap.get(datasetId);
            if (dataset == null) {
                log.warn("知识库不存在，跳过搜索: userId={}, datasetId={}", userId, datasetId);
                continue;
            }

            if (installedDatasetIds.contains(datasetId)) {
                accessibleDatasets.add(dataset);
                log.debug("用户 {} 已安装知识库 {}，允许搜索", userId, datasetId);
                continue;
            }

            if (Objects.equals(userId, dataset.getUserId())) {
                accessibleDatasets.add(dataset);
                log.debug("用户 {} 是知识库 {} 的创建者，允许搜索", userId, datasetId);
                continue;
            }

            log.warn("用户 {} 既没有安装知识库 {} 也不是创建者，跳过搜索", userId, datasetId);
        }

        ACCESSIBLE_DATASET_CACHE.put(cacheKey, new ArrayList<>(accessibleDatasets));
        return accessibleDatasets;
    }

    private List<DocumentUnitEntity> mergeGroupedResults(List<List<DocumentUnitEntity>> groupedResults, Integer maxResults) {
        if (groupedResults == null || groupedResults.isEmpty()) {
            return new ArrayList<>();
        }
        int finalMaxResults = maxResults != null ? Math.min(maxResults, HybridSearchConfig.ABSOLUTE_MAX_RESULTS)
                : HybridSearchConfig.DEFAULT_MAX_RESULTS;
        int adaptiveRrfK = resolveAdaptiveRrfK(groupedResults.size(), finalMaxResults);
        Map<String, GroupMergedResult> mergedById = new LinkedHashMap<>();

        for (List<DocumentUnitEntity> groupResult : groupedResults) {
            if (groupResult == null || groupResult.isEmpty()) {
                continue;
            }
            for (int rank = 0; rank < groupResult.size(); rank++) {
                DocumentUnitEntity entity = groupResult.get(rank);
                if (entity == null || entity.getId() == null) {
                    continue;
                }
                double reciprocalRankScore = 1.0 / (adaptiveRrfK + rank + 1);
                GroupMergedResult existing = mergedById.get(entity.getId());
                if (existing == null) {
                    mergedById.put(entity.getId(), new GroupMergedResult(entity, reciprocalRankScore));
                    continue;
                }
                existing.merge(entity, reciprocalRankScore);
            }
        }

        return mergedById.values().stream()
                .peek(GroupMergedResult::applyMergedScore)
                .sorted(Comparator.comparingDouble(GroupMergedResult::getMergedRankScore).reversed()
                        .thenComparing(result -> result.getEntity().getConfidenceTier(),
                                Comparator.nullsLast(Comparator.comparingInt(ConfidenceTier::getPriority).reversed()))
                        .thenComparing(result -> result.getEntity().getSimilarityScore(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(GroupMergedResult::getEntity)
                .limit(finalMaxResults)
                .collect(Collectors.toList());
    }

    private ModelConfig resolveCachedUserChatModelConfig(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        ModelConfig cached = USER_CHAT_MODEL_CONFIG_CACHE.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        ModelConfig loaded = userModelConfigResolver.getUserChatModelConfig(userId);
        if (loaded != null) {
            USER_CHAT_MODEL_CONFIG_CACHE.put(userId, loaded);
        }
        return loaded;
    }

    private List<DocumentUnitEntity> limitResults(List<DocumentUnitEntity> entities, Integer maxResults) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        int finalMaxResults = maxResults != null ? Math.min(maxResults, HybridSearchConfig.ABSOLUTE_MAX_RESULTS)
                : HybridSearchConfig.DEFAULT_MAX_RESULTS;
        return entities.stream().limit(finalMaxResults).collect(Collectors.toList());
    }

    private List<DocumentUnitDTO> getCachedFinalResult(String cacheKey) {
        return restoreDocumentUnitDTOs(FINAL_RAG_RESULT_CACHE.getIfPresent(cacheKey));
    }

    private void cacheFinalResult(String cacheKey, List<DocumentUnitDTO> results) {
        if (cacheKey == null || results == null) {
            return;
        }
        FINAL_RAG_RESULT_CACHE.put(cacheKey, snapshotDocumentUnitDTOs(results));
    }

    private String buildRawRetrievalCacheKey(String scope, String userId, String userRagId,
            List<RagQaDatasetEntity> datasets,
            RagSearchRequest request, ModelConfig chatModelConfig) {
        List<String> normalizedDatasetIds = datasets == null ? Collections.emptyList()
                : datasets.stream().filter(Objects::nonNull).map(RagQaDatasetEntity::getId).filter(Objects::nonNull)
                        .map(String::trim).filter(id -> !id.isEmpty()).sorted().toList();
        String normalizedQuestion = normalizeQuestionForCache(request.getQuestion());
        String raw = scope + "|" + userId + "|" + userRagId + "|" + normalizedDatasetIds + "|"
                + buildDatasetVersionFingerprint(datasets, normalizedDatasetIds) + "|"
                + normalizedQuestion + "|" + request.getEnableQueryExpansion() + "|"
                + request.getEnableHyde() + "|" + request.getAdjustedMinScore() + "|" + request.getFallbackMinScore()
                + "|" + request.getAdjustedCandidateMultiplier() + "|" + request.getTimeoutSeconds() + "|"
                + (chatModelConfig != null ? chatModelConfig.getModelEndpoint() : "no-chat-model");
        return sha256(raw);
    }

    private String normalizeQuestionForCache(String question) {
        if (question == null) {
            return "";
        }
        String normalized = question.trim().toLowerCase()
                .replace('？', '?')
                .replace('！', '!')
                .replace('，', ',')
                .replace('。', '.')
                .replaceAll("[\\s\\u3000]+", " ")
                .trim();
        normalized = normalized.replaceAll("[\\p{Punct}]+$", "").trim();
        return normalized;
    }

    private int resolveAdaptiveRrfK(int groupCount, int maxResults) {
        int safeGroups = Math.max(1, groupCount);
        int safeMaxResults = Math.max(1, maxResults);
        int configured = Math.max(1, ragProperties.getSearch().getRrfK());
        int adaptive = Math.max(10, safeMaxResults * Math.max(2, Math.min(4, safeGroups * 2)));
        return safeGroups <= 2 ? Math.min(configured, adaptive) : configured;
    }

    private void awaitGroupSearches(List<GroupSearchFuture> futures, RagSearchRequest request) {
        if (futures == null || futures.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] allFutures = futures.stream().map(GroupSearchFuture::future).toArray(CompletableFuture[]::new);
        long timeoutSeconds = Math.max(1, request.getTimeoutSeconds() == null ? 30 : request.getTimeoutSeconds()) + 2L;
        try {
            CompletableFuture.allOf(allFutures).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("分组检索整体超时: groups={}, timeoutSeconds={}", futures.size(), timeoutSeconds);
            futures.stream().filter(groupFuture -> !groupFuture.future().isDone())
                    .forEach(groupFuture -> groupFuture.future().cancel(true));
        } catch (Exception e) {
            log.warn("等待分组检索完成时异常: groups={}, err={}", futures.size(), e.getMessage());
        }
    }

    private GroupSearchExecutionResult resolveGroupSearchResult(GroupSearchFuture groupFuture) {
        if (groupFuture == null) {
            return null;
        }
        if (!groupFuture.future().isDone()) {
            return GroupSearchExecutionResult.failed(groupFuture.group().datasetIds(), "GROUP_SEARCH_TIMEOUT",
                    "分组检索整体超时");
        }
        try {
            return groupFuture.future().join();
        } catch (Exception e) {
            String errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return GroupSearchExecutionResult.failed(groupFuture.group().datasetIds(), "GROUP_RESULT_JOIN_FAILED",
                    errorMessage);
        }
    }

    private String buildDatasetVersionFingerprint(List<RagQaDatasetEntity> datasets, List<String> normalizedDatasetIds) {
        if (datasets == null || datasets.isEmpty()) {
            return "no-datasets";
        }
        String datasetFingerprint = datasets.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(RagQaDatasetEntity::getId, Comparator.nullsLast(String::compareTo)))
                .map(dataset -> String.join(":",
                        String.valueOf(dataset.getId()),
                        String.valueOf(dataset.getUpdatedAt()),
                        String.valueOf(dataset.getActiveEmbeddingProfileId()),
                        String.valueOf(dataset.getEmbeddingModelId()),
                        String.valueOf(dataset.getEmbeddingMigrationStatus())))
                .collect(Collectors.joining(","));
        return datasetFingerprint + "|files=" + buildFileVersionFingerprint(normalizedDatasetIds);
    }

    private String buildFileVersionFingerprint(List<String> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return "no-files";
        }
        List<String> normalizedDatasetIds = datasetIds.stream().filter(Objects::nonNull).map(String::trim)
                .filter(id -> !id.isEmpty()).sorted().toList();
        if (normalizedDatasetIds.isEmpty()) {
            return "no-files";
        }
        String cacheKey = String.join(",", normalizedDatasetIds);
        try {
            return FILE_VERSION_FINGERPRINT_CACHE.get(cacheKey,
                    () -> loadFileVersionFingerprint(normalizedDatasetIds));
        } catch (Exception e) {
            log.warn("构建RAG文件版本指纹失败，回退数据集指纹: datasets={}, err={}", datasetIds, e.getMessage());
            return "fingerprint-error";
        }
    }

    private String loadFileVersionFingerprint(List<String> datasetIds) {
        List<FileDetailEntity> files = fileDetailDomainService
                .listFileFingerprintsByDatasetIdsWithoutUserCheck(datasetIds);
        if (files == null || files.isEmpty()) {
            return "empty";
        }
        return files.stream().filter(Objects::nonNull)
                .map(file -> String.join(":",
                        String.valueOf(file.getDataSetId()),
                        String.valueOf(file.getId()),
                        String.valueOf(file.getUpdatedAt()),
                        String.valueOf(file.getProcessingStatus()),
                        String.valueOf(file.getCurrentEmbeddingPageNumber())))
                .collect(Collectors.joining(","));
    }

    private String buildFinalRetrievalCacheKey(String rawCacheKey, RagSearchRequest request) {
        String raw = rawCacheKey + "|" + request.getMaxResults() + "|" + request.getEnableRerank();
        return sha256(raw);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private int resolveRawCandidateLimit(RagSearchRequest request) {
        int requestedMaxResults = request.getMaxResults() == null ? HybridSearchConfig.DEFAULT_MAX_RESULTS
                : request.getMaxResults();
        int candidateMultiplier = request.getAdjustedCandidateMultiplier() == null ? 1
                : request.getAdjustedCandidateMultiplier();
        return Math.min(HybridSearchConfig.ABSOLUTE_MAX_RESULTS,
                Math.max(Math.max(1, requestedMaxResults) * Math.max(1, candidateMultiplier),
                        Math.max(10, ragProperties.getSearch().getRawCacheBaseLimit())));
    }

    private List<DocumentUnitEntity> finalizeResults(List<DocumentUnitEntity> rawEntities, Boolean enableRerank,
            String question, Integer maxResults, SearchProgressListener progressListener) {
        if (rawEntities == null || rawEntities.isEmpty()) {
            return new ArrayList<>();
        }
        List<DocumentUnitEntity> working = restoreDocumentUnitEntities(snapshotDocumentUnitEntities(rawEntities));
        if (enableRerank(enableRerank) && !working.isEmpty()) {
            int inputCount = working.size();
            working = hybridSearchDomainService.rerankDocumentUnits(working, question);
            SearchProgressListener safeProgressListener = progressListener == null ? SearchProgressListener.noop()
                    : progressListener;
            safeProgressListener.onRerankCompleted(inputCount, working == null ? 0 : working.size(), true);
        }
        return limitResults(working, maxResults);
    }

    private void notifyGroupProgress(SearchProgressListener progressListener, int completedGroups, int totalGroups,
            List<String> datasetIds, GroupSearchExecutionResult result) {
        if (progressListener == null) {
            return;
        }
        GroupSearchExecutionResult safeResult = result != null ? result
                : GroupSearchExecutionResult.failed(datasetIds, "GROUP_RESULT_NULL", "分组检索结果为空");
        progressListener.onGroupCompleted(completedGroups, totalGroups,
                datasetIds == null ? Collections.emptyList() : datasetIds, safeResult.isSuccess(), safeResult.isNoResults(),
                safeResult.documents().size(), safeResult.errorCode(), safeResult.errorMessage());
    }

    private String resolveThrowableMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown_error";
        }
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        if (cause instanceof TimeoutException || cause instanceof CancellationException) {
            return "分组检索超时";
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private Boolean enableRerank(Boolean enableRerank) {
        return Boolean.TRUE.equals(enableRerank);
    }

    private List<DocumentUnitEntity> getCachedRawResult(String cacheKey) {
        return restoreDocumentUnitEntities(RAW_RAG_RESULT_CACHE.getIfPresent(cacheKey));
    }

    private void cacheRawResult(String cacheKey, List<DocumentUnitEntity> results) {
        if (cacheKey == null || results == null) {
            return;
        }
        RAW_RAG_RESULT_CACHE.put(cacheKey, snapshotDocumentUnitEntities(results));
    }

    private List<CachedDocumentUnitDTO> snapshotDocumentUnitDTOs(List<DocumentUnitDTO> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return source.stream().filter(Objects::nonNull).map(CachedDocumentUnitDTO::fromDto).toList();
    }

    private List<DocumentUnitDTO> restoreDocumentUnitDTOs(List<CachedDocumentUnitDTO> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return source.stream().map(CachedDocumentUnitDTO::toDto).toList();
    }

    private List<CachedDocumentUnitEntity> snapshotDocumentUnitEntities(List<DocumentUnitEntity> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return source.stream().filter(Objects::nonNull).map(CachedDocumentUnitEntity::fromEntity).toList();
    }

    private List<DocumentUnitEntity> restoreDocumentUnitEntities(List<CachedDocumentUnitEntity> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return source.stream().map(CachedDocumentUnitEntity::toEntity).toList();
    }

    private static final class GroupMergedResult {
        private DocumentUnitEntity entity;
        private double mergedRankScore;

        private GroupMergedResult(DocumentUnitEntity entity, double mergedRankScore) {
            this.entity = entity;
            this.mergedRankScore = mergedRankScore;
        }

        private void merge(DocumentUnitEntity candidate, double reciprocalRankScore) {
            this.mergedRankScore += reciprocalRankScore;
            ConfidenceTier currentTier = entity.getConfidenceTier();
            ConfidenceTier candidateTier = candidate.getConfidenceTier();
            double currentScore = entity.getSimilarityScore() == null ? 0.0 : entity.getSimilarityScore();
            double candidateScore = candidate.getSimilarityScore() == null ? 0.0 : candidate.getSimilarityScore();
            boolean candidatePreferred = candidateTier != null
                    && (currentTier == null || candidateTier.getPriority() > currentTier.getPriority());
            if (candidatePreferred || (Objects.equals(currentTier, candidateTier) && candidateScore > currentScore)) {
                candidate.setConfidenceTier(ConfidenceTier.max(currentTier, candidateTier));
                this.entity = candidate;
            } else {
                entity.setConfidenceTier(ConfidenceTier.max(currentTier, candidateTier));
            }
        }

        private void applyMergedScore() {
            entity.setSimilarityScore(mergedRankScore);
        }

        private double getMergedRankScore() {
            return mergedRankScore;
        }

        private DocumentUnitEntity getEntity() {
            return entity;
        }
    }

    private record DatasetSearchGroup(List<String> datasetIds, EmbeddingModelFactory.EmbeddingConfig embeddingConfig,
            String vectorTableName, Integer vectorDimension) {
    }

    private record GroupSearchFuture(DatasetSearchGroup group, CompletableFuture<GroupSearchExecutionResult> future) {
    }

    private record GroupSearchExecutionResult(List<String> datasetIds,
            HybridSearchDomainService.HybridSearchExecutionResult result) {

        private static GroupSearchExecutionResult of(List<String> datasetIds,
                HybridSearchDomainService.HybridSearchExecutionResult result) {
            return new GroupSearchExecutionResult(datasetIds == null ? Collections.emptyList() : datasetIds,
                    result == null
                            ? HybridSearchDomainService.HybridSearchExecutionResult.failed(
                                    HybridSearchDomainService.HybridSearchStatus.SEARCH_ERROR, "GROUP_RESULT_NULL",
                                    "分组检索结果为空")
                            : result);
        }

        private static GroupSearchExecutionResult failed(List<String> datasetIds, String errorCode, String errorMessage) {
            return new GroupSearchExecutionResult(datasetIds == null ? Collections.emptyList() : datasetIds,
                    HybridSearchDomainService.HybridSearchExecutionResult.failed(
                            HybridSearchDomainService.HybridSearchStatus.SEARCH_ERROR, errorCode, errorMessage));
        }

        private boolean hasDocuments() {
            return result != null && result.getDocuments() != null && !result.getDocuments().isEmpty();
        }

        private boolean isSuccess() {
            return result != null && result.isSuccess();
        }

        private boolean isNoResults() {
            return result != null && result.getStatus() == HybridSearchDomainService.HybridSearchStatus.NO_RESULTS;
        }

        private boolean isFailure() {
            return result == null || result.isFailure();
        }

        private List<DocumentUnitEntity> documents() {
            return result == null ? Collections.emptyList() : result.getDocuments();
        }

        private String errorCode() {
            return result == null ? "GROUP_RESULT_NULL" : result.getErrorCode();
        }

        private String errorMessage() {
            return result == null ? "分组检索结果为空" : result.getErrorMessage();
        }
    }

    public interface SearchProgressListener {
        SearchProgressListener NOOP = new SearchProgressListener() {
        };

        default void onHydePrepared(HyDEDomainService.HyDEQueryPlan hydeQueryPlan, int totalGroups) {
        }

        default void onGroupCompleted(int completedGroups, int totalGroups, List<String> datasetIds, boolean success,
                boolean noResults, int documentCount, String errorCode, String errorMessage) {
        }

        default void onRerankCompleted(int inputCount, int outputCount, boolean applied) {
        }

        static SearchProgressListener noop() {
            return NOOP;
        }
    }

    public static final class SearchExecutionSummary {
        private final List<DocumentUnitDTO> documents;
        private final List<RagFailedGroupInfo> failedGroups;

        private SearchExecutionSummary(List<DocumentUnitDTO> documents, List<RagFailedGroupInfo> failedGroups) {
            this.documents = documents == null ? Collections.emptyList() : documents;
            this.failedGroups = failedGroups == null ? Collections.emptyList() : failedGroups;
        }

        public static SearchExecutionSummary success(List<DocumentUnitDTO> documents) {
            return new SearchExecutionSummary(documents, Collections.emptyList());
        }

        public static SearchExecutionSummary empty() {
            return new SearchExecutionSummary(Collections.emptyList(), Collections.emptyList());
        }

        public List<DocumentUnitDTO> getDocuments() {
            return documents;
        }

        public List<RagFailedGroupInfo> getFailedGroups() {
            return failedGroups;
        }
    }

    private record GroupedSearchSummary(List<DocumentUnitEntity> documents, List<RagFailedGroupInfo> failedGroups) {
        private static GroupedSearchSummary empty() {
            return new GroupedSearchSummary(Collections.emptyList(), Collections.emptyList());
        }
    }

    private record CachedDocumentUnitDTO(String id, String fileId, String filename, Integer page, Integer chunkIndex,
            String content, Double similarityScore, ConfidenceTier confidenceTier, Boolean isOcr, Boolean isVector,
            String createdAt, String updatedAt) {

        private static CachedDocumentUnitDTO fromDto(DocumentUnitDTO dto) {
            return new CachedDocumentUnitDTO(dto.getId(), dto.getFileId(), dto.getFilename(), dto.getPage(),
                    dto.getChunkIndex(), dto.getContent(), dto.getSimilarityScore(), dto.getConfidenceTier(),
                    dto.getIsOcr(), dto.getIsVector(), dto.getCreatedAt(), dto.getUpdatedAt());
        }

        private DocumentUnitDTO toDto() {
            DocumentUnitDTO dto = new DocumentUnitDTO();
            dto.setId(id);
            dto.setFileId(fileId);
            dto.setFilename(filename);
            dto.setPage(page);
            dto.setChunkIndex(chunkIndex);
            dto.setContent(content);
            dto.setSimilarityScore(similarityScore);
            dto.setConfidenceTier(confidenceTier);
            dto.setIsOcr(isOcr);
            dto.setIsVector(isVector);
            dto.setCreatedAt(createdAt);
            dto.setUpdatedAt(updatedAt);
            return dto;
        }
    }

    private record CachedDocumentUnitEntity(String id, String fileId, Integer page, Integer chunkIndex, String content,
            Boolean isVector, Boolean isOcr, Double similarityScore, ConfidenceTier confidenceTier,
            LocalDateTime createdAt, LocalDateTime updatedAt) {

        private static CachedDocumentUnitEntity fromEntity(DocumentUnitEntity entity) {
            return new CachedDocumentUnitEntity(entity.getId(), entity.getFileId(), entity.getPage(),
                    entity.getChunkIndex(), entity.getContent(), entity.getIsVector(), entity.getIsOcr(),
                    entity.getSimilarityScore(), entity.getConfidenceTier(), entity.getCreatedAt(), entity.getUpdatedAt());
        }

        private DocumentUnitEntity toEntity() {
            DocumentUnitEntity entity = new DocumentUnitEntity();
            entity.setId(id);
            entity.setFileId(fileId);
            entity.setPage(page);
            entity.setChunkIndex(chunkIndex);
            entity.setContent(content);
            entity.setIsVector(isVector);
            entity.setIsOcr(isOcr);
            entity.setSimilarityScore(similarityScore);
            entity.setConfidenceTier(confidenceTier);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);
            return entity;
        }
    }
}
