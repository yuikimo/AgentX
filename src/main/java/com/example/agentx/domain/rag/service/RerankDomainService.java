package com.example.agentx.domain.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.dto.req.RerankRequest;
import com.example.agentx.domain.rag.dto.resp.RerankResponse;
import com.example.agentx.infrastructure.rag.api.RerankForestApi;
import com.example.agentx.infrastructure.rag.config.RerankProperties;

/** @author shilong.zang
 * @date 16:11 <br/>
 */
@Service
public class RerankDomainService {

    @Resource
    private RerankProperties rerankProperties;

    @Resource
    private RerankForestApi rerankForestApi;

    private final RagProperties ragProperties;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntilMillis;

    public RerankDomainService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public record RerankResult(Integer index, Double relevanceScore) {
    }

    /** 重排序文档列表并返回重排分数
     *
     * @param documents 待重排序的文档文本列表
     * @param query 查询问题
     * @return 重排序后的文档索引及重排分数 */
    public List<RerankResult> rerankWithScores(List<String> documents, String query) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        if (query == null || query.trim().isEmpty()) {
            return buildOriginalOrderResults(documents.size());
        }

        if (isCircuitOpen()) {
            return buildOriginalOrderResults(documents.size());
        }

        try {
            List<RerankResult> results = rerankInBatches(documents, query);
            consecutiveFailures.set(0);
            return results.isEmpty() ? buildOriginalOrderResults(documents.size()) : results;
        } catch (Exception e) {
            recordFailure();
            return buildOriginalOrderResults(documents.size());
        }
    }

    /** 重排序文档列表
     * 
     * @param documents 待重排序的文档文本列表
     * @param query 查询问题
     * @return 重排序后的文档索引列表 */
    public List<Integer> rerank(List<String> documents, String query) {
        return rerankWithScores(documents, query).stream().map(RerankResult::index).toList();
    }

    /** 重排序文档（已废弃）
     * 
     * @deprecated 推荐使用 rerank(List&lt;String&gt; documents, String query) 方法 */
    @Deprecated
    public List<EmbeddingMatch<TextSegment>> rerankDocument(
            EmbeddingSearchResult<TextSegment> textSegmentEmbeddingSearchResult, String question) {

        // 提取文档文本列表
        final List<String> documents = textSegmentEmbeddingSearchResult.matches().stream()
                .map(text -> text.embedded().text()).toList();

        // 使用新的rerank方法
        final List<Integer> rerankedIndices = rerank(documents, question);

        // 根据重排序索引重新排列结果
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> originalMatches = textSegmentEmbeddingSearchResult.matches();

        rerankedIndices.forEach(index -> {
            if (index >= 0 && index < originalMatches.size()) {
                matches.add(originalMatches.get(index));
            }
        });

        return matches;
    }

    private List<RerankResult> buildOriginalOrderResults(int size) {
        if (size <= 0) {
            return new ArrayList<>();
        }
        return IntStream.range(0, size).mapToObj(index -> new RerankResult(index, null)).toList();
    }

    private List<RerankResult> rerankInBatches(List<String> documents, String query) {
        int batchSize = Math.max(1, ragProperties.getRerank().getBatchSize());
        if (documents.size() <= batchSize) {
            return callRerankWithRetry(documents, query, 0);
        }

        List<RerankResult> merged = new ArrayList<>(documents.size());
        for (int start = 0; start < documents.size(); start += batchSize) {
            int end = Math.min(documents.size(), start + batchSize);
            merged.addAll(callRerankWithRetry(documents.subList(start, end), query, start));
        }
        return merged.stream()
                .sorted((left, right) -> Double.compare(scoreOrDefault(right.relevanceScore()),
                        scoreOrDefault(left.relevanceScore())))
                .toList();
    }

    private List<RerankResult> callRerankWithRetry(List<String> documents, String query, int indexOffset) {
        int attempts = Math.max(1, ragProperties.getRerank().getRetryAttempts() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                final RerankRequest rerankRequest = new RerankRequest();
                rerankRequest.setModel(rerankProperties.getModel());
                rerankRequest.setQuery(query);
                rerankRequest.setDocuments(prepareDocuments(documents));
                rerankRequest.setReturnDocuments(rerankProperties.isReturnDocuments());
                rerankRequest.setMaxChunksPerDoc(rerankProperties.getMaxChunksPerDoc());
                rerankRequest.setOverlapTokens(rerankProperties.getOverlapTokens());

                final RerankResponse rerankResponse = rerankForestApi.rerank(rerankProperties.getApiUrl(),
                        rerankProperties.getApiKey(), rerankRequest);
                final List<RerankResponse.SearchResult> results = rerankResponse == null ? null
                        : rerankResponse.getResults();
                if (results == null || results.isEmpty()) {
                    return buildOriginalOrderResults(documents.size()).stream()
                            .map(result -> new RerankResult(result.index() + indexOffset, result.relevanceScore()))
                            .toList();
                }
                return results.stream()
                        .filter(result -> result.getIndex() != null && result.getIndex() >= 0
                                && result.getIndex() < documents.size())
                        .map(result -> new RerankResult(result.getIndex() + indexOffset, result.getRelevanceScore()))
                        .toList();
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw lastError == null ? new IllegalStateException("rerank failed") : lastError;
    }

    private boolean isCircuitOpen() {
        long openUntil = circuitOpenUntilMillis;
        return openUntil > 0 && System.currentTimeMillis() < openUntil;
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= Math.max(1, ragProperties.getRerank().getCircuitBreaker().getFailureThreshold())) {
            circuitOpenUntilMillis = System.currentTimeMillis()
                    + Math.max(1000L, ragProperties.getRerank().getCircuitBreaker().getOpenMs());
            consecutiveFailures.set(0);
        }
    }

    private double scoreOrDefault(Double score) {
        return score == null ? 0.0 : score;
    }

    private List<String> prepareDocuments(List<String> documents) {
        int maxDocumentChars = Math.max(0, rerankProperties.getMaxDocumentChars());
        return documents.stream().map(document -> truncateDocument(document, maxDocumentChars)).toList();
    }

    private String truncateDocument(String document, int maxDocumentChars) {
        if (document == null) {
            return "";
        }
        if (maxDocumentChars <= 0 || document.length() <= maxDocumentChars) {
            return document;
        }
        return document.substring(0, maxDocumentChars);
    }

}
