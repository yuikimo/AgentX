package com.example.agentx.domain.rag.service;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.rag.constant.ConfidenceTier;
import com.example.agentx.domain.rag.constant.EmbeddingDistanceMetric;
import com.example.agentx.domain.rag.constant.FileProcessingStatusEnum;
import com.example.agentx.domain.rag.constant.MetadataConstant;
import com.example.agentx.domain.rag.constant.SearchType;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.message.RagDocSyncStorageMessage;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.model.VectorStoreResult;
import com.example.agentx.domain.rag.repository.DocumentUnitRepository;
import com.example.agentx.domain.rag.repository.FileDetailRepository;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;
import com.example.agentx.infrastructure.rag.service.EmbeddingStoreRouter;

/** 向量存储领域服务（支持按Embedding Profile动态路由） */
@Component
public class EmbeddingDomainService implements MetadataConstant {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDomainService.class);
    private static final String EMBEDDING_PROFILE_ID = "EMBEDDING_PROFILE_ID";

    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreRouter embeddingStoreRouter;
    private final EmbeddingProfileDomainService embeddingProfileDomainService;
    private final FileDetailRepository fileDetailRepository;
    private final DocumentUnitRepository documentUnitRepository;
    private final RagProperties ragProperties;

    public EmbeddingDomainService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreRouter embeddingStoreRouter,
            EmbeddingProfileDomainService embeddingProfileDomainService, FileDetailRepository fileDetailRepository,
            DocumentUnitRepository documentUnitRepository, RagProperties ragProperties) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreRouter = embeddingStoreRouter;
        this.embeddingProfileDomainService = embeddingProfileDomainService;
        this.fileDetailRepository = fileDetailRepository;
        this.documentUnitRepository = documentUnitRepository;
        this.ragProperties = ragProperties;
    }

    /** 纯向量检索（默认表，兼容旧逻辑） */
    public List<VectorStoreResult> vectorSearch(List<String> dataSetIds, String question, Integer maxResults,
            Double minScore, Boolean enableRerank, Integer candidateMultiplier,
            EmbeddingModelFactory.EmbeddingConfig embeddingConfig) {
        return vectorSearch(dataSetIds, question, maxResults, minScore, null, enableRerank, candidateMultiplier,
                embeddingConfig, embeddingStoreRouter.getDefaultTableName(), embeddingStoreRouter.getDefaultDimension());
    }

    /** 纯向量检索（指定向量表） */
    public List<VectorStoreResult> vectorSearch(List<String> dataSetIds, String question, Integer maxResults,
            Double minScore, Double fallbackMinScore, Boolean enableRerank, Integer candidateMultiplier,
            EmbeddingModelFactory.EmbeddingConfig embeddingConfig, String vectorTableName, Integer vectorDimension) {
        if (dataSetIds == null || dataSetIds.isEmpty()) {
            log.warn("数据集ID列表为空，无法进行向量搜索");
            return Collections.emptyList();
        }
        if (!StringUtils.hasText(question)) {
            log.warn("查询问题为空，无法进行向量搜索");
            return Collections.emptyList();
        }
        if (embeddingConfig == null) {
            log.warn("嵌入模型配置为空，无法进行向量搜索");
            return Collections.emptyList();
        }

        int finalMaxResults = maxResults != null ? Math.min(maxResults, 100) : 15;
        double finalMinScore = minScore != null ? Math.max(0.0, Math.min(minScore, 1.0)) : 0.7;
        Double finalFallbackMinScore = normalizeFallbackMinScore(fallbackMinScore, finalMinScore);
        boolean finalEnableRerank = enableRerank != null ? enableRerank : true;
        int finalCandidateMultiplier = candidateMultiplier != null ? Math.max(1, Math.min(candidateMultiplier, 5)) : 2;
        String finalTableName = StringUtils.hasText(vectorTableName) ? vectorTableName
                : embeddingStoreRouter.getDefaultTableName();
        Integer finalDimension = vectorDimension != null ? vectorDimension : embeddingStoreRouter.getDefaultDimension();
        int searchLimit = finalEnableRerank
                ? Math.max(finalMaxResults * finalCandidateMultiplier, 30)
                : finalMaxResults;
        double queryMinScore = finalFallbackMinScore != null ? finalFallbackMinScore : finalMinScore;

        long startTime = System.currentTimeMillis();
        try {
            OpenAiEmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(embeddingConfig);
            Embedding queryEmbedding = Embedding.from(embeddingModel.embed(question).content().vector());
            return vectorSearchByEmbedding(dataSetIds, question, queryEmbedding, finalMaxResults, finalMinScore,
                    finalFallbackMinScore, finalEnableRerank, finalCandidateMultiplier, finalTableName, finalDimension,
                    startTime);
        } catch (Exception e) {
            log.error("向量搜索异常，table={}, query='{}'", finalTableName, question, e);
            return Collections.emptyList();
        }
    }

    /** HyDE 向量融合搜索：原始问题与假设文档分别向量化后加权平均 */
    public List<VectorStoreResult> vectorSearchWithHydeFusion(List<String> dataSetIds, String originalQuestion,
            String hypotheticalDocument, Integer maxResults, Double minScore, Double fallbackMinScore,
            Boolean enableRerank, Integer candidateMultiplier, EmbeddingModelFactory.EmbeddingConfig embeddingConfig,
            String vectorTableName, Integer vectorDimension) {
        if (!StringUtils.hasText(hypotheticalDocument)) {
            return vectorSearch(dataSetIds, originalQuestion, maxResults, minScore, fallbackMinScore, enableRerank,
                    candidateMultiplier, embeddingConfig, vectorTableName, vectorDimension);
        }
        if (dataSetIds == null || dataSetIds.isEmpty() || !StringUtils.hasText(originalQuestion)
                || embeddingConfig == null) {
            return vectorSearch(dataSetIds, originalQuestion, maxResults, minScore, fallbackMinScore, enableRerank,
                    candidateMultiplier, embeddingConfig, vectorTableName, vectorDimension);
        }

        int finalMaxResults = maxResults != null ? Math.min(maxResults, 100) : 15;
        double finalMinScore = minScore != null ? Math.max(0.0, Math.min(minScore, 1.0)) : 0.7;
        Double finalFallbackMinScore = normalizeFallbackMinScore(fallbackMinScore, finalMinScore);
        boolean finalEnableRerank = enableRerank != null ? enableRerank : true;
        int finalCandidateMultiplier = candidateMultiplier != null ? Math.max(1, Math.min(candidateMultiplier, 5)) : 2;
        String finalTableName = StringUtils.hasText(vectorTableName) ? vectorTableName
                : embeddingStoreRouter.getDefaultTableName();
        Integer finalDimension = vectorDimension != null ? vectorDimension : embeddingStoreRouter.getDefaultDimension();
        long startTime = System.currentTimeMillis();
        try {
            OpenAiEmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(embeddingConfig);
            Embedding queryEmbedding = fuseHydeEmbeddings(embeddingModel, originalQuestion, hypotheticalDocument);
            return vectorSearchByEmbedding(dataSetIds, originalQuestion, queryEmbedding, finalMaxResults, finalMinScore,
                    finalFallbackMinScore, finalEnableRerank, finalCandidateMultiplier, finalTableName, finalDimension,
                    startTime);
        } catch (Exception e) {
            log.warn("HyDE向量融合失败，回退原始向量检索，table={}, query='{}', err={}", finalTableName, originalQuestion,
                    e.getMessage());
            return vectorSearch(dataSetIds, originalQuestion, maxResults, minScore, fallbackMinScore, enableRerank,
                    candidateMultiplier, embeddingConfig, vectorTableName, vectorDimension);
        }
    }

    private List<VectorStoreResult> vectorSearchByEmbedding(List<String> dataSetIds, String question,
            Embedding queryEmbedding, int finalMaxResults, double finalMinScore, Double finalFallbackMinScore,
            boolean finalEnableRerank, int finalCandidateMultiplier, String finalTableName, Integer finalDimension,
            long startTime) {
        try {
            EmbeddingStore<TextSegment> embeddingStore = embeddingStoreRouter.getOrCreateStore(
                    "table:" + finalTableName, finalTableName, finalDimension, EmbeddingDistanceMetric.COSINE);
            int searchLimit = finalEnableRerank
                    ? Math.max(finalMaxResults * finalCandidateMultiplier, finalMaxResults)
                    : finalMaxResults;
            double queryMinScore = finalFallbackMinScore != null ? finalFallbackMinScore : finalMinScore;

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(EmbeddingSearchRequest.builder()
                    .filter(new IsIn(DATA_SET_ID, dataSetIds))
                    .maxResults(searchLimit)
                    .minScore(queryMinScore)
                    .queryEmbedding(queryEmbedding)
                    .build());

            List<EmbeddingMatch<TextSegment>> embeddingMatches = searchResult.matches();
            boolean fallbackUsed = false;
            if (finalFallbackMinScore != null && !embeddingMatches.isEmpty()) {
                List<EmbeddingMatch<TextSegment>> primaryMatches = embeddingMatches.stream()
                        .filter(match -> match.score() >= finalMinScore).limit(finalMaxResults).toList();
                if (!primaryMatches.isEmpty()) {
                    embeddingMatches = primaryMatches;
                } else {
                    embeddingMatches = embeddingMatches.stream().limit(finalMaxResults).toList();
                    fallbackUsed = true;
                    log.info("向量搜索触发fallback: table={}, query='{}', primaryMinScore={}, fallbackMinScore={}, hits={}",
                            finalTableName, question, finalMinScore, finalFallbackMinScore, embeddingMatches.size());
                }
            } else {
                embeddingMatches = embeddingMatches.stream().limit(finalMaxResults).toList();
            }
            final boolean finalFallbackUsed = fallbackUsed;

            List<VectorStoreResult> results = embeddingMatches.stream().map(match -> {
                VectorStoreResult result = new VectorStoreResult();
                result.setEmbeddingId(match.embeddingId());
                result.setText(match.embedded().text());
                result.setMetadata(match.embedded().metadata().toMap());
                result.setScore(match.score());
                result.setSearchType(SearchType.VECTOR);
                result.setMatchedMinScore(finalFallbackUsed ? finalFallbackMinScore : finalMinScore);
                result.setConfidenceTier(resolveConfidenceTier(match.score(), finalMinScore, finalFallbackUsed));
                return result;
            }).toList();

            log.info("向量搜索完成，table={}, query='{}'，返回{}条，耗时{}ms", finalTableName, question, results.size(),
                    System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception e) {
            log.error("向量搜索异常，table={}, query='{}'", finalTableName, question, e);
            return Collections.emptyList();
        }
    }

    private Embedding fuseHydeEmbeddings(OpenAiEmbeddingModel embeddingModel, String originalQuestion,
            String hypotheticalDocument) {
        List<TextSegment> segments = new ArrayList<>(2);
        segments.add(new TextSegment(originalQuestion, new Metadata()));
        segments.add(new TextSegment(hypotheticalDocument, new Metadata()));
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings == null || embeddings.size() < 2 || embeddings.get(0) == null || embeddings.get(1) == null) {
            throw new BusinessException("HyDE向量融合失败：向量化结果为空");
        }
        float[] original = embeddings.get(0).vector();
        float[] hypothetical = embeddings.get(1).vector();
        if (original == null || hypothetical == null || original.length != hypothetical.length) {
            throw new BusinessException("HyDE向量融合失败：向量维度不一致");
        }
        double originalWeight = Math.max(0.0, ragProperties.getHyde().getVectorFusion().getOriginalWeight());
        double hypotheticalWeight = Math.max(0.0, ragProperties.getHyde().getVectorFusion().getHypotheticalWeight());
        double totalWeight = originalWeight + hypotheticalWeight;
        if (totalWeight <= 0) {
            originalWeight = 1.0;
            hypotheticalWeight = 0.0;
            totalWeight = 1.0;
        }
        float[] fused = new float[original.length];
        for (int i = 0; i < original.length; i++) {
            fused[i] = (float) ((original[i] * originalWeight + hypothetical[i] * hypotheticalWeight) / totalWeight);
        }
        return Embedding.from(fused);
    }

    private Double normalizeFallbackMinScore(Double fallbackMinScore, double primaryMinScore) {
        if (fallbackMinScore == null) {
            return null;
        }
        double normalized = Math.max(0.0, Math.min(fallbackMinScore, 1.0));
        if (normalized >= primaryMinScore) {
            return null;
        }
        return normalized;
    }

    private ConfidenceTier resolveConfidenceTier(double score, double primaryMinScore, boolean fallbackUsed) {
        if (fallbackUsed) {
            return ConfidenceTier.FALLBACK;
        }
        double highConfidenceThreshold = Math.min(1.0, primaryMinScore + 0.1);
        if (score >= highConfidenceThreshold) {
            return ConfidenceTier.HIGH;
        }
        return ConfidenceTier.LOW;
    }

    /** 批量删除向量数据（跨Profile表） */
    public void deleteEmbedding(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        try {
            EmbeddingStore<TextSegment> defaultStore = embeddingStoreRouter.getOrCreateStore("default",
                    embeddingStoreRouter.getDefaultTableName(), embeddingStoreRouter.getDefaultDimension(),
                    EmbeddingDistanceMetric.COSINE);
            defaultStore.removeAll(metadataKey(MetadataConstant.FILE_ID).isIn(fileIds));
        } catch (Exception e) {
            log.warn("删除默认向量表数据失败: {}", e.getMessage());
        }

        for (var profile : embeddingProfileDomainService.listAllProfiles()) {
            try {
                EmbeddingStore<TextSegment> store = embeddingStoreRouter.getOrCreateStore(profile.getId(),
                        profile.getTableName(), profile.getDimension(),
                        EmbeddingDistanceMetric.valueOf(profile.getDistanceMetric()));
                store.removeAll(metadataKey(MetadataConstant.FILE_ID).isIn(fileIds));
            } catch (Exception e) {
                log.warn("删除Profile向量表数据失败: profileId={}, error={}", profile.getId(), e.getMessage());
            }
        }
    }

    /** 文本向量化入库 */
    public void syncStorage(RagDocSyncStorageMessage ragDocSyncStorageMessage) {
        String vectorId = ragDocSyncStorageMessage.getId();
        FileDetailEntity fileDetailEntity = fileDetailRepository.selectById(ragDocSyncStorageMessage.getFileId());
        if (fileDetailEntity == null) {
            throw new BusinessException("文件不存在，无法向量化");
        }

        String content = ragDocSyncStorageMessage.getContent();
        if (!StringUtils.hasText(content)) {
            log.warn("存储消息{}中内容为空，跳过向量化", vectorId);
            return;
        }

        Metadata metadata = buildMetadata(ragDocSyncStorageMessage);
        TextSegment textSegment = new TextSegment(content, metadata);

        OpenAiEmbeddingModel embeddingModel = createEmbeddingModelFromMessage(ragDocSyncStorageMessage);
        Embedding embeddings = embeddingModel.embed(textSegment).content();

        EmbeddingStore<TextSegment> store = resolveStoreFromMessage(ragDocSyncStorageMessage);
        store.add(embeddings, textSegment);

        String originalDocId = extractOriginalDocId(vectorId);
        if (originalDocId != null) {
            documentUnitRepository.update(Wrappers.lambdaUpdate(com.example.agentx.domain.rag.model.DocumentUnitEntity.class)
                    .eq(com.example.agentx.domain.rag.model.DocumentUnitEntity::getId, originalDocId)
                    .set(com.example.agentx.domain.rag.model.DocumentUnitEntity::getIsVector, true));
        }

        Integer pageSize = fileDetailEntity.getFilePageSize();
        Long isVector = documentUnitRepository.selectCount(Wrappers.lambdaQuery(com.example.agentx.domain.rag.model.DocumentUnitEntity.class)
                .eq(com.example.agentx.domain.rag.model.DocumentUnitEntity::getFileId, ragDocSyncStorageMessage.getFileId())
                .eq(com.example.agentx.domain.rag.model.DocumentUnitEntity::getIsVector, true));
        Integer vectorizedCount = Convert.toInt(isVector);

        if (pageSize != null && vectorizedCount >= pageSize) {
            fileDetailRepository.update(Wrappers.lambdaUpdate(FileDetailEntity.class)
                    .eq(FileDetailEntity::getId, fileDetailEntity.getId())
                    .set(FileDetailEntity::getProcessingStatus, FileProcessingStatusEnum.COMPLETED.getCode()));
        }
    }

    private EmbeddingStore<TextSegment> resolveStoreFromMessage(RagDocSyncStorageMessage message) {
        String profileId = message.getEmbeddingProfileId();
        if (StringUtils.hasText(profileId)) {
            var profile = embeddingProfileDomainService.getProfileById(profileId);
            if (profile != null) {
                return embeddingStoreRouter.getOrCreateStore(profile.getId(), profile.getTableName(),
                        profile.getDimension(), EmbeddingDistanceMetric.valueOf(profile.getDistanceMetric()));
            }
            log.warn("消息中的embeddingProfileId不存在，回退默认向量表: {}", profileId);
        }
        return embeddingStoreRouter.getOrCreateStore("default", embeddingStoreRouter.getDefaultTableName(),
                embeddingStoreRouter.getDefaultDimension(), EmbeddingDistanceMetric.COSINE);
    }

    private String extractOriginalDocId(String vectorId) {
        if (vectorId == null) {
            return null;
        }
        if (vectorId.contains("_segment_")) {
            return vectorId.substring(0, vectorId.indexOf("_segment_"));
        }
        return vectorId;
    }

    private Metadata buildMetadata(RagDocSyncStorageMessage message) {
        Metadata metadata = new Metadata();
        metadata.put(FILE_ID, message.getFileId());
        metadata.put(FILE_NAME, message.getFileName());
        metadata.put(DOCUMENT_ID, extractOriginalDocId(message.getId()));
        metadata.put(DATA_SET_ID, message.getDatasetId());
        if (StringUtils.hasText(message.getEmbeddingProfileId())) {
            metadata.put(EMBEDDING_PROFILE_ID, message.getEmbeddingProfileId());
        }
        return metadata;
    }

    private OpenAiEmbeddingModel createEmbeddingModelFromMessage(RagDocSyncStorageMessage message) {
        if (message == null || message.getEmbeddingModelConfig() == null) {
            String errorMsg = String.format("用户 %s 未配置嵌入模型，无法进行向量化处理",
                    message != null ? message.getUserId() : "unknown");
            throw new BusinessException(errorMsg);
        }
        try {
            var modelConfig = message.getEmbeddingModelConfig();
            EmbeddingModelFactory.EmbeddingConfig config = new EmbeddingModelFactory.EmbeddingConfig(
                    modelConfig.getApiKey(), modelConfig.getBaseUrl(), modelConfig.getModelEndpoint());
            return embeddingModelFactory.createEmbeddingModel(config);
        } catch (Exception e) {
            String errorMsg = String.format("用户 %s 创建嵌入模型失败: %s", message.getUserId(), e.getMessage());
            throw new BusinessException(errorMsg, e);
        }
    }
}
