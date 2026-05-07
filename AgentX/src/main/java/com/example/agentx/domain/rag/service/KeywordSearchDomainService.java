package com.example.agentx.domain.rag.service;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.rag.constant.ConfidenceTier;
import com.example.agentx.domain.rag.constant.SearchType;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.model.VectorStoreResult;
import com.example.agentx.domain.rag.repository.VectorStoreSearchRepository;
import com.example.agentx.infrastructure.rag.service.EmbeddingStoreRouter;

/** 关键词检索领域服务（支持动态向量表） */
@Service
public class KeywordSearchDomainService {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchDomainService.class);

    private final VectorStoreSearchRepository vectorStoreSearchRepository;
    private final EmbeddingStoreRouter embeddingStoreRouter;
    private final RagProperties ragProperties;

    public KeywordSearchDomainService(VectorStoreSearchRepository vectorStoreSearchRepository,
            EmbeddingStoreRouter embeddingStoreRouter, RagProperties ragProperties) {
        this.vectorStoreSearchRepository = vectorStoreSearchRepository;
        this.embeddingStoreRouter = embeddingStoreRouter;
        this.ragProperties = ragProperties;
    }

    /** 兼容旧逻辑：默认向量表 */
    public List<VectorStoreResult> keywordSearch(List<String> dataSetIds, String userQuery, Integer maxResults) {
        return keywordSearch(dataSetIds, userQuery, maxResults, embeddingStoreRouter.getDefaultTableName());
    }

    /** 指定向量表进行关键词检索 */
    public List<VectorStoreResult> keywordSearch(List<String> dataSetIds, String userQuery, Integer maxResults,
            String vectorTableName) {
        if (dataSetIds == null || dataSetIds.isEmpty()) {
            log.warn("数据集ID列表为空，无法进行关键词搜索");
            return Collections.emptyList();
        }
        if (!StringUtils.hasText(userQuery)) {
            log.warn("用户查询为空，无法进行关键词搜索");
            return Collections.emptyList();
        }
        int finalMaxResults = maxResults == null || maxResults <= 0 ? 20 : maxResults;
        String finalTableName = StringUtils.hasText(vectorTableName) ? vectorTableName
                : embeddingStoreRouter.getDefaultTableName();

        long startTime = System.currentTimeMillis();
        try {
            String normalizedQuery = normalizeKeywordQuery(userQuery);
            List<VectorStoreResult> results = vectorStoreSearchRepository.keywordSearch(finalTableName, dataSetIds,
                    normalizedQuery, finalMaxResults);
            for (VectorStoreResult result : results) {
                result.setSearchType(SearchType.KEYWORD);
                result.setConfidenceTier(resolveKeywordConfidenceTier(result.getScore()));
            }
            log.info("关键词搜索完成，table={}, query='{}', normalized='{}'，返回{}条，耗时{}ms", finalTableName, userQuery,
                    normalizedQuery, results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception e) {
            log.error("关键词搜索失败，table={}, query='{}'", finalTableName, userQuery, e);
            return Collections.emptyList();
        }
    }

    private String normalizeKeywordQuery(String userQuery) {
        String normalized = userQuery == null ? "" : userQuery.trim()
                .replaceAll("[\\p{Punct}，。！？；：“”‘’（）【】、《》]+", " ").replaceAll("\\s+", " ");
        if (!StringUtils.hasText(normalized)) {
            return userQuery;
        }
        Set<String> stopWords = ragProperties.getKeyword().getStopWords().stream().map(String::trim)
                .filter(StringUtils::hasText).collect(Collectors.toSet());
        String filtered = Arrays.stream(normalized.split("\\s+")).filter(StringUtils::hasText)
                .filter(token -> !stopWords.contains(token)).collect(Collectors.joining(" ")).trim();
        return StringUtils.hasText(filtered) ? filtered : normalized;
    }

    private ConfidenceTier resolveKeywordConfidenceTier(Double score) {
        double normalizedScore = score == null ? 0.0 : score;
        if (normalizedScore >= ragProperties.getKeyword().getHighThreshold()) {
            return ConfidenceTier.HIGH;
        }
        if (normalizedScore >= ragProperties.getKeyword().getLowThreshold()) {
            return ConfidenceTier.LOW;
        }
        return ConfidenceTier.FALLBACK;
    }
}
