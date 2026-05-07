package com.example.agentx.domain.memory.service;

import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.IMPORTANCE;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.ITEM_ID;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.MEMORY_TYPE;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.STATUS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.SOURCE_SESSION_ID;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.TAGS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.UPDATED_AT_EPOCH_MS;
import static com.example.agentx.domain.memory.constant.MemoryMetadataConstant.USER_ID;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.parseImportance;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.parseMemoryType;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.parseTags;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.parseUpdatedAt;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.safeCacheKeyPart;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.safeImportance;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.safeList;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.sha256;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.stringValue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.memory.config.MemoryRecallProperties;
import com.example.agentx.domain.memory.model.MemoryItemEntity;
import com.example.agentx.domain.memory.model.MemoryResult;
import com.example.agentx.domain.memory.model.MemorySearchFilter;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.domain.memory.repository.MemoryItemRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class MemoryRecaller {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecaller.class);

    private static final int ACTIVE = 1;
    private static final int MAX_RECALL_TOP_K = 16;
    private static final int RECALL_CACHE_MAX_SIZE = 5000;
    private static final int RECALL_CACHE_VERSION_MAX_SIZE = 50000;
    private static final Duration RECALL_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration RECALL_CACHE_VERSION_TTL = Duration.ofHours(1);
    private static final int HIT_UPDATE_THROTTLE_MAX_SIZE = 20000;
    private static final Duration HIT_UPDATE_THROTTLE_TTL = Duration.ofSeconds(45);

    private final MemoryItemRepository memoryItemRepository;
    private final MemoryEmbeddingModelProvider embeddingModelProvider;
    private final MemoryRecallProperties memoryRecallProperties;
    private final EmbeddingStore<TextSegment> memoryEmbeddingStore;
    private final Cache<String, List<MemoryResult>> recallCache;
    private final Cache<String, AtomicLong> recallCacheVersions;
    private final Cache<String, HitCounter> hitUpdateAccumulator;

    public MemoryRecaller(MemoryItemRepository memoryItemRepository,
            MemoryEmbeddingModelProvider embeddingModelProvider,
            @Qualifier("memoryEmbeddingStore") EmbeddingStore<TextSegment> memoryEmbeddingStore,
            MemoryRecallProperties memoryRecallProperties) {
        this.memoryItemRepository = memoryItemRepository;
        this.embeddingModelProvider = embeddingModelProvider;
        this.memoryEmbeddingStore = memoryEmbeddingStore;
        this.memoryRecallProperties = memoryRecallProperties;
        this.recallCache = CacheBuilder.newBuilder().maximumSize(RECALL_CACHE_MAX_SIZE)
                .expireAfterWrite(RECALL_CACHE_TTL).recordStats().build();
        this.recallCacheVersions = CacheBuilder.newBuilder().maximumSize(RECALL_CACHE_VERSION_MAX_SIZE)
                .expireAfterAccess(RECALL_CACHE_VERSION_TTL.toHours(), TimeUnit.HOURS).build();
        this.hitUpdateAccumulator = CacheBuilder.newBuilder().maximumSize(HIT_UPDATE_THROTTLE_MAX_SIZE)
                .expireAfterWrite(HIT_UPDATE_THROTTLE_TTL)
                .removalListener((RemovalNotification<String, HitCounter> notification) ->
                        flushPendingHitCounter(notification.getValue()))
                .build();
    }

    public List<MemoryResult> searchRelevant(String userId, String query, int topK) {
        return searchRelevant(userId, query, topK, MemorySearchFilter.empty());
    }

    public List<MemoryResult> searchRelevant(String userId, String query, int topK, MemorySearchFilter filter) {
        String normalizedQuery = normalizeQuery(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            log.debug("记忆召回跳过：query为空，userId={}", userId);
            return Collections.emptyList();
        }
        int k = Math.max(1, Math.min(topK, MAX_RECALL_TOP_K));
        MemorySearchFilter effectiveFilter = filter == null ? MemorySearchFilter.empty() : filter;
        String cacheKey = buildRecallCacheKey(userId, normalizedQuery, k, effectiveFilter);
        List<MemoryResult> cachedResults = recallCache.getIfPresent(cacheKey);
        if (cachedResults != null) {
            log.debug("命中记忆召回缓存，userId={}, topK={}, queryLength={}", userId, k, normalizedQuery.length());
            recordMemoryHits(userId, cachedResults, true);
            return copyMemoryResults(cachedResults);
        }
        log.debug("开始记忆召回，userId={}, topK={}, queryLength={}", userId, k, normalizedQuery.length());

        try {
            Embedding queryEmbedding = embeddingModelProvider.resolveEmbeddingModel(userId).embed(normalizedQuery).content();
            List<EmbeddingMatch<TextSegment>> matches = searchMemoryMatches(userId, queryEmbedding, k);
            if (CollectionUtils.isEmpty(matches)) {
                log.debug("记忆召回向量层无命中，userId={}", userId);
                return Collections.emptyList();
            }

            List<String> itemIds = matches.stream().map(this::resolveItemIdFromMatch).filter(StringUtils::hasText).distinct()
                    .collect(Collectors.toList());
            if (itemIds.isEmpty()) {
                log.debug("记忆召回候选为空（无有效itemId），userId={}", userId);
                return Collections.emptyList();
            }

            LambdaQueryWrapper<MemoryItemEntity> itemQuery = Wrappers.<MemoryItemEntity>lambdaQuery()
                    .eq(MemoryItemEntity::getStatus, ACTIVE).in(MemoryItemEntity::getId, itemIds);
            appendSearchScopeConstraint(itemQuery, effectiveFilter);
            List<MemoryItemEntity> items = memoryItemRepository.selectList(itemQuery).stream()
                    .filter(item -> matchesTagFilter(item, effectiveFilter)).toList();
            Map<String, MemoryItemEntity> itemMap = items.stream()
                    .collect(Collectors.toMap(MemoryItemEntity::getId, item -> item, (first, ignored) -> first));
            if (itemMap.isEmpty()) {
                log.debug("记忆召回候选被过滤器筛空，userId={}, scopeAgentId={}", userId, effectiveFilter.getScopeAgentId());
                return Collections.emptyList();
            }
            int maxHitCount = itemMap.values().stream().filter(Objects::nonNull).map(MemoryItemEntity::getHitCount)
                    .filter(Objects::nonNull).max(Integer::compareTo).orElse(0);

            Map<String, ScoredMemoryResult> bestResultByItemId = new LinkedHashMap<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                String itemId = resolveItemIdFromMatch(match);
                if (!StringUtils.hasText(itemId)) {
                    continue;
                }
                MemoryItemEntity fallbackItem = itemMap.get(itemId);
                RecallCandidate candidate = buildRecallCandidate(match, fallbackItem);
                if (candidate == null) {
                    continue;
                }
                double score = calculateRecallScore(match.score(), candidate.importance(), candidate.updatedAt(),
                        candidate.type(), normalizedQuery, candidate.hitCount(), maxHitCount);
                MemoryResult result = new MemoryResult();
                result.setItemId(itemId);
                result.setType(candidate.type());
                result.setText(candidate.text());
                result.setSourceSessionId(candidate.sourceSessionId());
                result.setImportance(candidate.importance());
                result.setTags(candidate.tags());
                result.setUpdatedAt(candidate.updatedAt());
                result.setScore(score);
                ScoredMemoryResult scoredResult = new ScoredMemoryResult(result, normalizeVector(match.embedding()));
                bestResultByItemId.merge(itemId, scoredResult,
                        (current, incoming) -> current.result().getScore() >= incoming.result().getScore() ? current
                                : incoming);
            }

            List<MemoryResult> sortedResults = selectDiverseResults(new ArrayList<>(bestResultByItemId.values()), k);
            recallCache.put(cacheKey, copyMemoryResults(sortedResults));
            recordMemoryHits(userId, sortedResults, false);
            log.debug("记忆召回完成，userId={}, 命中条数={}", userId, sortedResults.size());
            return copyMemoryResults(sortedResults);
        } catch (Exception e) {
            log.error("记忆检索失败 userId={}, err={}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public Page<MemoryItemEntity> pageMemories(String userId, String type, String keyword, int page, int pageSize) {
        Page<MemoryItemEntity> resultPage = new Page<>(Math.max(1, page), Math.max(1, pageSize));
        LambdaQueryWrapper<MemoryItemEntity> query = Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE);
        if (type != null && !type.isBlank()) {
            query.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
        }
        if (StringUtils.hasText(keyword)) {
            String trimmedKeyword = keyword.trim();
            String likePattern = "%" + trimmedKeyword + "%";
            query.and(wrapper -> wrapper.like(MemoryItemEntity::getText, trimmedKeyword)
                    .or().apply(
                            "EXISTS (SELECT 1 FROM jsonb_array_elements_text(COALESCE(tags, '[]'::jsonb)) AS tag(value) "
                                    + "WHERE tag.value ILIKE {0})",
                            likePattern));
        }
        query.orderByDesc(MemoryItemEntity::getUpdatedAt);
        memoryItemRepository.selectPage(resultPage, query);
        return resultPage;
    }

    public List<MemoryItemEntity> listMemories(String userId, String type, Integer limit) {
        LambdaQueryWrapper<MemoryItemEntity> query = Wrappers.<MemoryItemEntity>lambdaQuery()
                .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE);
        if (type != null && !type.isBlank()) {
            query.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
        }
        query.orderByDesc(MemoryItemEntity::getUpdatedAt);
        List<MemoryItemEntity> list = memoryItemRepository.selectList(query);
        if (limit != null && limit > 0 && list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    public long getRecallCacheVersion(String userId) {
        String cacheKey = String.valueOf(userId);
        try {
            return recallCacheVersions.get(cacheKey, AtomicLong::new).get();
        } catch (ExecutionException e) {
            return 0L;
        }
    }

    public void invalidateUserRecallCache(String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        try {
            recallCacheVersions.get(userId, AtomicLong::new).incrementAndGet();
        } catch (ExecutionException e) {
            log.debug("更新记忆召回缓存版本失败，userId={}, err={}", userId, e.getMessage());
        }
        log.debug("已清理记忆召回缓存，userId={}", userId);
    }

    private List<EmbeddingMatch<TextSegment>> searchMemoryMatches(String userId, Embedding queryEmbedding, int topK) {
        int initialMaxResults = Math.max(1, topK);
        List<EmbeddingMatch<TextSegment>> initialMatches = doMemoryVectorSearch(userId, queryEmbedding, initialMaxResults);
        if (!shouldExpandRecallSearch(initialMatches, topK)) {
            return initialMatches;
        }

        int multiplier = Math.max(1, memoryRecallProperties.getCandidateMultiplier());
        int expandedMaxResults = Math.min(MAX_RECALL_TOP_K * multiplier, Math.max(initialMaxResults, topK * multiplier));
        if (expandedMaxResults <= initialMaxResults) {
            return initialMatches;
        }
        return doMemoryVectorSearch(userId, queryEmbedding, expandedMaxResults);
    }

    private boolean shouldExpandRecallSearch(List<EmbeddingMatch<TextSegment>> matches, int topK) {
        if (CollectionUtils.isEmpty(matches) || matches.size() < topK) {
            return true;
        }
        double kthScore = clamp01(matches.get(topK - 1).score());
        if (kthScore >= memoryRecallProperties.getHighConfidenceScore()) {
            return false;
        }
        int windowSize = Math.min(topK, matches.size());
        double[] scores = matches.stream().limit(windowSize).mapToDouble(match -> clamp01(match.score())).toArray();
        double mean = Arrays.stream(scores).average().orElse(0);
        double variance = Arrays.stream(scores).map(score -> Math.pow(score - mean, 2)).average().orElse(0);
        double stddev = Math.sqrt(variance);
        double topGap = scores.length > 0 ? Math.max(0, scores[0] - scores[scores.length - 1]) : 0;
        return !(mean >= memoryRecallProperties.getDistributionMeanThreshold()
                && stddev <= memoryRecallProperties.getDistributionStddevThreshold()
                && topGap <= memoryRecallProperties.getDistributionTopGapThreshold());
    }

    private List<EmbeddingMatch<TextSegment>> doMemoryVectorSearch(String userId, Embedding queryEmbedding, int maxResults) {
        Filter filter = new IsEqualTo(USER_ID, userId).and(new IsEqualTo(STATUS, String.valueOf(ACTIVE)));
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder().filter(filter).maxResults(maxResults)
                .minScore(memoryRecallProperties.getMinScore()).queryEmbedding(queryEmbedding).build();
        EmbeddingSearchResult<TextSegment> result = memoryEmbeddingStore.search(request);
        return result == null || result.matches() == null ? Collections.emptyList() : result.matches();
    }

    private void recordMemoryHits(String userId, List<MemoryResult> results, boolean throttleDuplicates) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(results)) {
            return;
        }
        List<String> itemIds = results.stream().filter(Objects::nonNull).map(MemoryResult::getItemId)
                .filter(StringUtils::hasText).distinct().toList();
        if (itemIds.isEmpty()) {
            return;
        }
        if (throttleDuplicates) {
            recordThrottledMemoryHits(userId, itemIds);
            return;
        }
        incrementMemoryHits(userId, itemIds, 1);
    }

    private void recordThrottledMemoryHits(String userId, List<String> itemIds) {
        hitUpdateAccumulator.cleanUp();
        List<String> firstHits = new ArrayList<>();
        for (String itemId : itemIds) {
            String normalizedItemId = itemId == null ? "" : itemId.trim();
            if (!StringUtils.hasText(normalizedItemId)) {
                continue;
            }
            String cacheKey = buildHitUpdateThrottleKey(userId, normalizedItemId);
            HitCounter hitCounter = hitUpdateAccumulator.getIfPresent(cacheKey);
            if (hitCounter == null) {
                hitUpdateAccumulator.put(cacheKey, new HitCounter(userId, normalizedItemId));
                firstHits.add(normalizedItemId);
                continue;
            }
            hitCounter.increment();
        }
        if (!firstHits.isEmpty()) {
            incrementMemoryHits(userId, firstHits, 1);
        }
    }

    private void flushPendingHitCounter(HitCounter hitCounter) {
        if (hitCounter == null) {
            return;
        }
        int pendingHits = hitCounter.drain();
        if (pendingHits <= 0) {
            return;
        }
        incrementMemoryHits(hitCounter.userId(), List.of(hitCounter.itemId()), pendingHits);
    }

    private void incrementMemoryHits(String userId, List<String> itemIds, int increment) {
        if (!StringUtils.hasText(userId) || CollectionUtils.isEmpty(itemIds) || increment <= 0) {
            return;
        }
        try {
            memoryItemRepository.update(null, Wrappers.<MemoryItemEntity>lambdaUpdate()
                    .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getStatus, ACTIVE)
                    .in(MemoryItemEntity::getId, itemIds).set(MemoryItemEntity::getLastHitAt, LocalDateTime.now())
                    .setSql("hit_count = COALESCE(hit_count, 0) + " + increment));
        } catch (Exception e) {
            log.debug("记录记忆召回命中失败，userId={}, itemIds={}, err={}", userId, itemIds, e.getMessage());
        }
    }

    private String buildHitUpdateThrottleKey(String userId, String itemId) {
        return String.valueOf(userId) + ":" + sha256(String.valueOf(itemId));
    }

    private double calculateRecallScore(Double similarity, Float importanceValue, LocalDateTime updatedAt,
            MemoryType memoryType, String normalizedQuery, Integer hitCount, int maxHitCount) {
        double similarityScore = clamp01(similarity == null ? 0 : similarity);
        double importanceScore = clamp01(importanceValue == null ? 0.5 : importanceValue);
        double recencyScore = calculateRecencyScore(updatedAt, memoryType);
        double typePrior = calculateTypePrior(memoryType, normalizedQuery);
        double frequencyScore = calculateFrequencyScore(hitCount, maxHitCount);
        RecallWeights weights = resolveRecallWeights(normalizedQuery);
        double score = similarityScore * weights.similarityWeight()
                + importanceScore * weights.importanceWeight()
                + recencyScore * weights.recencyWeight()
                + typePrior * weights.typePriorWeight()
                + frequencyScore * weights.frequencyWeight();
        return clamp01(score);
    }

    private RecallWeights resolveRecallWeights(String normalizedQuery) {
        double similarityWeight = Math.max(0, memoryRecallProperties.getSimilarityWeight());
        double importanceWeight = Math.max(0, memoryRecallProperties.getImportanceWeight());
        double recencyWeight = Math.max(0, memoryRecallProperties.getRecencyWeight());
        double typePriorWeight = Math.max(0, memoryRecallProperties.getTypePriorWeight());
        double frequencyWeight = Math.max(0, memoryRecallProperties.getFrequencyWeight());

        MemoryType preferredType = inferPreferredMemoryType(normalizedQuery);
        if (preferredType != null) {
            switch (preferredType) {
                case FACT -> similarityWeight += Math.max(0, memoryRecallProperties.getIntentSimilarityBoost());
                case PROFILE -> {
                    importanceWeight += Math.max(0, memoryRecallProperties.getIntentImportanceBoost());
                    typePriorWeight += Math.max(0, memoryRecallProperties.getIntentTypePriorBoost());
                }
                case TASK -> {
                    importanceWeight += Math.max(0, memoryRecallProperties.getIntentImportanceBoost() / 2);
                    recencyWeight += Math.max(0, memoryRecallProperties.getIntentRecencyBoost());
                    typePriorWeight += Math.max(0, memoryRecallProperties.getIntentTypePriorBoost());
                }
                case EPISODIC -> recencyWeight += Math.max(0, memoryRecallProperties.getIntentRecencyBoost());
            }
        }
        double totalWeight = similarityWeight + importanceWeight + recencyWeight + typePriorWeight + frequencyWeight;
        if (totalWeight <= 0) {
            return new RecallWeights(1, 0, 0, 0, 0);
        }
        return new RecallWeights(similarityWeight / totalWeight, importanceWeight / totalWeight,
                recencyWeight / totalWeight, typePriorWeight / totalWeight, frequencyWeight / totalWeight);
    }

    private double calculateFrequencyScore(Integer hitCount, int maxHitCount) {
        int safeHitCount = Math.max(0, hitCount == null ? 0 : hitCount);
        int safeMaxHitCount = Math.max(safeHitCount, maxHitCount);
        if (safeHitCount <= 0 || safeMaxHitCount <= 0) {
            return 0;
        }
        double numerator = Math.log1p(safeHitCount);
        double denominator = Math.log1p(safeMaxHitCount);
        if (denominator <= 0) {
            return 0;
        }
        return clamp01(numerator / denominator);
    }

    private double calculateRecencyScore(LocalDateTime updatedAt, MemoryType memoryType) {
        if (updatedAt == null) {
            return 0.5;
        }
        long ageDays = Math.max(0, ChronoUnit.DAYS.between(updatedAt, LocalDateTime.now()));
        double tau = Math.max(1.0, resolveTimeDecayDays(memoryType));
        return clamp01(Math.exp(-ageDays / tau));
    }

    private double calculateTypePrior(MemoryType type, String normalizedQuery) {
        MemoryType safeType = type == null ? MemoryType.FACT : type;
        double prior = switch (safeType) {
            case PROFILE -> 0.95;
            case FACT -> 0.85;
            case TASK -> 0.75;
            case EPISODIC -> 0.55;
        };
        MemoryType preferredType = inferPreferredMemoryType(normalizedQuery);
        if (preferredType == safeType) {
            prior += 0.15;
        } else if (preferredType != null && safeType == MemoryType.EPISODIC) {
            prior -= 0.10;
        }
        return clamp01(prior);
    }

    private MemoryType inferPreferredMemoryType(String normalizedQuery) {
        if (containsAny(normalizedQuery, memoryRecallProperties.getProfileKeywords())) {
            return MemoryType.PROFILE;
        }
        if (containsAny(normalizedQuery, memoryRecallProperties.getTaskKeywords())) {
            return MemoryType.TASK;
        }
        if (containsAny(normalizedQuery, memoryRecallProperties.getFactKeywords())) {
            return MemoryType.FACT;
        }
        if (containsAny(normalizedQuery, memoryRecallProperties.getEpisodicKeywords())) {
            return MemoryType.EPISODIC;
        }
        return null;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<MemoryResult> selectDiverseResults(List<ScoredMemoryResult> candidates, int topK) {
        if (CollectionUtils.isEmpty(candidates)) {
            return Collections.emptyList();
        }
        List<ScoredMemoryResult> remaining = candidates.stream()
                .sorted(Comparator.comparing((ScoredMemoryResult result) -> result.result().getScore()).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
        List<ScoredMemoryResult> selected = new ArrayList<>();
        double lambda = clamp01(memoryRecallProperties.getMmrLambda());
        while (!remaining.isEmpty() && selected.size() < topK) {
            ScoredMemoryResult best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (ScoredMemoryResult candidate : remaining) {
                double diversityPenalty = maxEmbeddingSimilarity(candidate, selected);
                double mmrScore = lambda * candidate.result().getScore() - (1.0 - lambda) * diversityPenalty;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            remaining.remove(best);
        }
        return selected.stream().map(ScoredMemoryResult::result).collect(Collectors.toList());
    }

    private double maxEmbeddingSimilarity(ScoredMemoryResult candidate, List<ScoredMemoryResult> selected) {
        if (candidate == null || CollectionUtils.isEmpty(selected)) {
            return 0;
        }
        float[] normalizedVector = candidate.normalizedVector();
        if (normalizedVector == null) {
            return 0;
        }
        double maxSimilarity = 0;
        for (ScoredMemoryResult selectedResult : selected) {
            maxSimilarity = Math.max(maxSimilarity, normalizedDotProduct(normalizedVector, selectedResult.normalizedVector()));
        }
        return maxSimilarity;
    }

    private float[] normalizeVector(Embedding embedding) {
        if (embedding == null || embedding.vector() == null || embedding.vector().length == 0) {
            return null;
        }
        float[] vector = embedding.vector();
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm <= 0) {
            return null;
        }
        double sqrtNorm = Math.sqrt(norm);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / sqrtNorm);
        }
        return normalized;
    }

    private double normalizedDotProduct(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null || vectorA.length == 0 || vectorA.length != vectorB.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < vectorA.length; i++) {
            dot += vectorA[i] * vectorB[i];
        }
        return clamp01(dot);
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private String resolveItemIdFromMatch(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null || match.embedded().metadata() == null) {
            return null;
        }
        return stringValue(metadataValue(match.embedded().metadata(), ITEM_ID));
    }

    private boolean hasRecallMetadata(Metadata metadata) {
        return metadataContains(metadata, IMPORTANCE) && metadataContains(metadata, UPDATED_AT_EPOCH_MS)
                && metadataContains(metadata, MEMORY_TYPE);
    }

    private RecallCandidate buildRecallCandidate(EmbeddingMatch<TextSegment> match, MemoryItemEntity fallbackItem) {
        if (match == null || match.embedded() == null) {
            return fallbackItem == null ? null : RecallCandidate.fromEntity(fallbackItem);
        }
        Metadata metadata = match.embedded().metadata();
        if (hasRecallMetadata(metadata)) {
            return RecallCandidate.fromMetadata(match.embedded(), metadata, fallbackItem);
        }
        return fallbackItem == null ? null : RecallCandidate.fromEntity(fallbackItem);
    }

    private static Object metadataValue(Metadata metadata, String key) {
        return metadata == null || metadata.toMap() == null ? null : metadata.toMap().get(key);
    }

    private static boolean metadataContains(Metadata metadata, String key) {
        return metadata != null && metadata.toMap() != null && metadata.toMap().containsKey(key);
    }

    private void appendSearchScopeConstraint(LambdaQueryWrapper<MemoryItemEntity> query, MemorySearchFilter filter) {
        if (query == null || filter == null) {
            return;
        }
        if (filter.hasScopeAgentId()) {
            if (filter.isIncludeGlobalScope()) {
                query.and(wrapper -> wrapper.eq(MemoryItemEntity::getScopeAgentId, filter.getScopeAgentId())
                        .or().isNull(MemoryItemEntity::getScopeAgentId)
                        .or().eq(MemoryItemEntity::getScopeAgentId, ""));
            } else {
                query.eq(MemoryItemEntity::getScopeAgentId, filter.getScopeAgentId());
            }
            return;
        }
        if (filter.isIncludeGlobalScope()) {
            query.and(wrapper -> wrapper.isNull(MemoryItemEntity::getScopeAgentId)
                    .or().eq(MemoryItemEntity::getScopeAgentId, ""));
        }
    }

    private boolean matchesTagFilter(MemoryItemEntity item, MemorySearchFilter filter) {
        if (filter == null || !filter.hasTags()) {
            return true;
        }
        if (item == null || CollectionUtils.isEmpty(item.getTags())) {
            return false;
        }
        Set<String> itemTags = item.getTags().stream().filter(StringUtils::hasText)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return filter.getTags().stream().filter(StringUtils::hasText).map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .anyMatch(itemTags::contains);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：“”‘’（）【】、《》]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        Set<String> stopWords = memoryRecallProperties.getQueryStopWords().stream().filter(StringUtils::hasText)
                .map(word -> word.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String filtered = Arrays.stream(normalized.split(" ")).filter(StringUtils::hasText)
                .filter(token -> !stopWords.contains(token)).collect(Collectors.joining(" ")).trim();
        return StringUtils.hasText(filtered) ? filtered : normalized;
    }

    private String buildRecallCacheKey(String userId, String normalizedQuery, int topK, MemorySearchFilter filter) {
        long version = getRecallCacheVersion(userId);
        String filterKey = buildSearchFilterCacheKey(filter);
        return String.join(":", String.valueOf(userId), String.valueOf(version), String.valueOf(topK),
                sha256(normalizedQuery), filterKey);
    }

    private String buildSearchFilterCacheKey(MemorySearchFilter filter) {
        if (filter == null) {
            return "no-filter";
        }
        String tags = filter.getTags() == null ? ""
                : filter.getTags().stream().filter(StringUtils::hasText).map(String::trim).sorted()
                        .collect(Collectors.joining(","));
        return "scope=" + safeCacheKeyPart(filter.getScopeAgentId()) + "|global=" + filter.isIncludeGlobalScope()
                + "|tags=" + sha256(tags);
    }

    private double resolveTimeDecayDays(MemoryType type) {
        MemoryType safeType = type == null ? MemoryType.FACT : type;
        return switch (safeType) {
            case PROFILE -> memoryRecallProperties.getProfileTimeDecayDays();
            case TASK -> memoryRecallProperties.getTaskTimeDecayDays();
            case FACT -> memoryRecallProperties.getFactTimeDecayDays();
            case EPISODIC -> memoryRecallProperties.getEpisodicTimeDecayDays();
        };
    }

    private static List<MemoryResult> copyMemoryResults(List<MemoryResult> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return source.stream().map(MemoryRecaller::copyMemoryResult).collect(Collectors.toList());
    }

    private static MemoryResult copyMemoryResult(MemoryResult source) {
        MemoryResult result = new MemoryResult();
        result.setItemId(source.getItemId());
        result.setType(source.getType());
        result.setText(source.getText());
        result.setSourceSessionId(source.getSourceSessionId());
        result.setImportance(source.getImportance());
        result.setScore(source.getScore());
        result.setTags(source.getTags() == null ? null : new ArrayList<>(source.getTags()));
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private record ScoredMemoryResult(MemoryResult result, float[] normalizedVector) {
    }

    private record RecallWeights(double similarityWeight, double importanceWeight, double recencyWeight,
            double typePriorWeight, double frequencyWeight) {
    }

    private static final class HitCounter {
        private final String userId;
        private final String itemId;
        private final AtomicInteger pendingHits = new AtomicInteger();

        private HitCounter(String userId, String itemId) {
            this.userId = userId;
            this.itemId = itemId;
        }

        private void increment() {
            pendingHits.incrementAndGet();
        }

        private int drain() {
            return pendingHits.getAndSet(0);
        }

        private String userId() {
            return userId;
        }

        private String itemId() {
            return itemId;
        }
    }

    private record RecallCandidate(MemoryType type, String text, String sourceSessionId, Float importance, List<String> tags,
            LocalDateTime updatedAt, Integer hitCount) {

        static RecallCandidate fromMetadata(TextSegment segment, Metadata metadata, MemoryItemEntity fallbackItem) {
            return new RecallCandidate(parseMemoryType(metadataValue(metadata, MEMORY_TYPE)),
                    segment == null ? null : segment.text(), stringValue(metadataValue(metadata, SOURCE_SESSION_ID)),
                    parseImportance(metadataValue(metadata, IMPORTANCE)),
                    parseTags(metadataValue(metadata, TAGS)), parseUpdatedAt(metadataValue(metadata, UPDATED_AT_EPOCH_MS)),
                    fallbackItem != null ? fallbackItem.getHitCount() : 0);
        }

        static RecallCandidate fromEntity(MemoryItemEntity entity) {
            if (entity == null) {
                return null;
            }
            LocalDateTime updatedAt = entity.getUpdatedAt() == null ? entity.getCreatedAt() : entity.getUpdatedAt();
            return new RecallCandidate(parseMemoryType(entity.getType()), entity.getText(), entity.getSourceSessionId(),
                    safeImportance(entity.getImportance()), safeList(entity.getTags()), updatedAt, entity.getHitCount());
        }
    }
}
