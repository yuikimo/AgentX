package com.example.agentx.domain.rag.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.rag.config.RagProperties;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.infrastructure.llm.LLMProviderService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.config.ProviderConfigFactory;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.util.Arrays;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/** HyDE（假设文档嵌入）领域服务 使用用户配置的LLM生成假设文档来改善RAG检索效果
 * 
 * @author claude */
@Service
public class HyDEDomainService {

    private static final Logger log = LoggerFactory.getLogger(HyDEDomainService.class);
    private static final String HYDE_PROMPT_VERSION = "v2";
    private static final Cache<String, String> HYDE_CACHE = CacheBuilder.newBuilder().maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES).build();
    private static final AtomicLong REQUEST_COUNTER = new AtomicLong();
    private static final AtomicLong CACHE_HIT_COUNTER = new AtomicLong();
    private static final AtomicLong SKIP_COUNTER = new AtomicLong();
    private static final AtomicLong FAILURE_COUNTER = new AtomicLong();
    private static final Map<String, Long> HYDE_IN_FLIGHT = new ConcurrentHashMap<>();

    /** HyDE提示词模板 */
    private static final String HYDE_PROMPT_TEMPLATE = """
            你是一个为 RAG 系统服务的查询扩展专家，负责生成用于增强检索的“假想文档摘要”。

            你的任务是：
            1.  **识别核心实体：** 精准识别用户问题中提到的具体项目、产品、技术或任何专有名词。
            2.  **生成假想摘要：** 围绕这些核心实体，设想一个能够完美回答用户问题的理想文档，并生成该文档的摘要。
            3.  **强制包含实体：** 在生成的摘要中，你必须多次使用或明确提及这些核心实体，确保摘要内容与实体紧密相关。
            4.  **融入通用概念：** 在此基础上，融入相关的通用核心概念、解决方案和专业术语，以丰富查询的语义。

            你的输出将与原始查询拼接，共同用于向量检索。请直接生成摘要，不超过100字，不要提问。
            """;

    private static final String DIRECT_FACT_PATTERN = "(?i).*(\\?|？|谁是|什么是|哪一年|第几|多少|几月|CEO|CTO|CFO|价格|版本|官网|地址).*";
    private static final String PROPER_NOUN_PATTERN = ".*([A-Z]{2,}[A-Za-z0-9_-]*|[A-Z][a-z]+[A-Z][A-Za-z0-9_-]*).*";
    private static final String CHINESE_NUMERAL_PATTERN = ".*[零一二三四五六七八九十百千万亿两〇0-9].*";

    private final ProviderConfigFactory providerConfigFactory;
    private final RagProperties ragProperties;

    public HyDEDomainService(ProviderConfigFactory providerConfigFactory, RagProperties ragProperties) {
        this.providerConfigFactory = providerConfigFactory;
        this.ragProperties = ragProperties;
    }

    public HyDEQueryPlan prepareQueryPlan(String query, ModelConfig chatModelConfig) {
        long requestId = REQUEST_COUNTER.incrementAndGet();
        String originalQuery = StringUtils.hasText(query) ? query.trim() : "";
        String queryHash = queryHash(originalQuery, chatModelConfig);

        if (!StringUtils.hasText(originalQuery)) {
            long skipped = SKIP_COUNTER.incrementAndGet();
            log.info("HyDE skipped: requestId={}, queryHash={}, reason=empty_query, skippedCount={}", requestId,
                    queryHash, skipped);
            return HyDEQueryPlan.skip(originalQuery, "empty_query", queryHash);
        }

        if (chatModelConfig == null || !chatModelConfig.isChatType()) {
            long skipped = SKIP_COUNTER.incrementAndGet();
            log.info("HyDE skipped: requestId={}, queryHash={}, reason=missing_chat_model_config, skippedCount={}",
                    requestId, queryHash, skipped);
            return HyDEQueryPlan.skip(originalQuery, "missing_chat_model_config", queryHash);
        }

        String skipReason = shouldSkipHyde(originalQuery);
        if (skipReason != null) {
            long skipped = SKIP_COUNTER.incrementAndGet();
            log.info("HyDE skipped: requestId={}, queryHash={}, reason={}, skippedCount={}", requestId, queryHash,
                    skipReason, skipped);
            return HyDEQueryPlan.skip(originalQuery, skipReason, queryHash);
        }

        String cacheKey = buildCacheKey(originalQuery, chatModelConfig);
        String cachedDocument = HYDE_CACHE.getIfPresent(cacheKey);
        if (StringUtils.hasText(cachedDocument)) {
            long cacheHits = CACHE_HIT_COUNTER.incrementAndGet();
            log.info("HyDE cache hit: requestId={}, queryHash={}, cacheHits={}", requestId, queryHash, cacheHits);
            return HyDEQueryPlan.applied(originalQuery, cachedDocument, true, queryHash);
        }

        HyDEQueryPlan generatedPlan = tryGenerateSynchronously(cacheKey, originalQuery, chatModelConfig, queryHash,
                requestId);
        if (generatedPlan != null) {
            return generatedPlan;
        }

        triggerAsyncWarmup(cacheKey, originalQuery, chatModelConfig, queryHash);
        log.info("HyDE cache miss sync timeout, async warmup triggered: requestId={}, queryHash={}", requestId,
                queryHash);
        return HyDEQueryPlan.skip(originalQuery, "cache_miss_sync_timeout", queryHash);
    }

    /** 生成假设文档 使用用户配置的LLM根据查询问题生成假设文档，用于改善向量检索效果
     * 
     * @param query 用户查询问题
     * @param chatModelConfig 聊天模型配置
     * @return 生成的假设文档文本，生成失败时返回原始查询 */
    public String generateHypotheticalDocument(String query, ModelConfig chatModelConfig) {
        HyDEQueryPlan plan = prepareQueryPlan(query, chatModelConfig);
        return plan.isHydeApplied() ? plan.getHypotheticalDocument() : plan.getOriginalQuery();
    }

    private String generateHypotheticalDocumentInternal(String query, ModelConfig chatModelConfig, String queryHash) {
        String trimmedQuery = query.trim();

        log.debug("开始HyDE生成，queryHash={}, 模型: {}", queryHash, chatModelConfig.getModelEndpoint());

        ProviderConfig providerConfig = providerConfigFactory.fromModelConfig(chatModelConfig,
                Duration.ofSeconds(Math.max(1, ragProperties.getHyde().getGenerationTimeoutSeconds())));
        providerConfig.setCacheTools(false);
        ChatModel chatModel = LLMProviderService.getStrand(chatModelConfig.getProtocol(), providerConfig);

        SystemMessage systemMessage = new SystemMessage(resolveHydePromptTemplate());
        UserMessage userMessage = new UserMessage(trimmedQuery);
        ChatResponse response = chatModel.chat(Arrays.asList(systemMessage, userMessage));
        String hypotheticalDocument = response.aiMessage() != null ? response.aiMessage().text() : "";
        String normalizedDocument = StringUtils.hasText(hypotheticalDocument) ? hypotheticalDocument.trim() : "";
        if (!StringUtils.hasText(normalizedDocument)) {
            throw new IllegalStateException("HyDE generated empty document");
        }
        return normalizedDocument;
    }

    private void triggerAsyncWarmup(String cacheKey, String query, ModelConfig chatModelConfig, String queryHash) {
        cleanupExpiredInFlight();
        if (HYDE_IN_FLIGHT.putIfAbsent(cacheKey, System.currentTimeMillis()) != null) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    String hypotheticalDocument = generateHypotheticalDocumentInternal(query, chatModelConfig, queryHash);
                    HYDE_CACHE.put(cacheKey, hypotheticalDocument);
                    log.info("HyDE async warmup completed: queryHash={}, latencyMs={}, hypotheticalLength={}", queryHash,
                            System.currentTimeMillis() - startTime, hypotheticalDocument.length());
                } catch (Exception e) {
                    long failures = FAILURE_COUNTER.incrementAndGet();
                    log.warn("HyDE async warmup failed: queryHash={}, error={}, failureCount={}", queryHash,
                            e.getMessage(), failures);
                } finally {
                    HYDE_IN_FLIGHT.remove(cacheKey);
                }
            });
        } catch (RuntimeException e) {
            HYDE_IN_FLIGHT.remove(cacheKey);
            throw e;
        }
    }

    private HyDEQueryPlan tryGenerateSynchronously(String cacheKey, String originalQuery, ModelConfig chatModelConfig,
            String queryHash, long requestId) {
        try {
            String hypotheticalDocument = CompletableFuture
                    .supplyAsync(() -> generateHypotheticalDocumentInternal(originalQuery, chatModelConfig, queryHash))
                    .get(Math.max(100L, ragProperties.getHyde().getSyncTimeoutMs()), TimeUnit.MILLISECONDS);
            if (StringUtils.hasText(hypotheticalDocument)) {
                HYDE_CACHE.put(cacheKey, hypotheticalDocument);
                log.info("HyDE generated synchronously: requestId={}, queryHash={}, hypotheticalLength={}", requestId,
                        queryHash, hypotheticalDocument.length());
                return HyDEQueryPlan.applied(originalQuery, hypotheticalDocument, false, queryHash);
            }
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            long failures = FAILURE_COUNTER.incrementAndGet();
            log.warn("HyDE sync generation failed: requestId={}, queryHash={}, error={}, failureCount={}", requestId,
                    queryHash, e.getMessage(), failures);
        }
        return null;
    }

    private String shouldSkipHyde(String query) {
        if (!StringUtils.hasText(query)) {
            return "empty_query";
        }

        String trimmedQuery = query.trim();

        if (trimmedQuery.length() > 200) {
            return "query_too_long";
        }

        if (trimmedQuery.matches(resolvePattern(ragProperties.getHyde().getDirectFactPattern(), DIRECT_FACT_PATTERN))) {
            return "direct_fact_query";
        }

        if (trimmedQuery.matches(resolvePattern(ragProperties.getHyde().getChineseNumeralPattern(),
                CHINESE_NUMERAL_PATTERN))) {
            return "contains_numeric_signal";
        }

        if (looksLikeShortEntityQuery(trimmedQuery)) {
            return "short_entity_query";
        }

        if (trimmedQuery.matches(resolvePattern(ragProperties.getHyde().getProperNounPattern(), PROPER_NOUN_PATTERN))) {
            return "contains_proper_noun";
        }

        return null;
    }

    private void cleanupExpiredInFlight() {
        long now = System.currentTimeMillis();
        long ttl = Math.max(1000L, ragProperties.getHyde().getInFlightTtlMs());
        HYDE_IN_FLIGHT.entrySet().removeIf(entry -> entry.getValue() == null || now - entry.getValue() > ttl);
    }

    private String resolveHydePromptTemplate() {
        return StringUtils.hasText(ragProperties.getHyde().getPromptTemplate())
                ? ragProperties.getHyde().getPromptTemplate()
                : HYDE_PROMPT_TEMPLATE;
    }

    private String resolvePattern(String override, String fallback) {
        return StringUtils.hasText(override) ? override : fallback;
    }

    private boolean looksLikeShortEntityQuery(String query) {
        if (query.length() <= 8) {
            return true;
        }
        String[] tokens = query.split("\\s+");
        if (tokens.length <= 3 && query.length() <= 16) {
            return true;
        }
        return query.chars().filter(Character::isLetterOrDigit).count() == query.length() && query.length() <= 12;
    }

    private String buildCacheKey(String query, ModelConfig chatModelConfig) {
        return queryHash(query, chatModelConfig);
    }

    private String queryHash(String query, ModelConfig chatModelConfig) {
        String raw = HYDE_PROMPT_VERSION + "|" + (chatModelConfig != null ? chatModelConfig.getProtocol() : "null")
                + "|" + (chatModelConfig != null ? chatModelConfig.getBaseUrl() : "null") + "|"
                + (chatModelConfig != null ? chatModelConfig.getModelEndpoint() : "null") + "|"
                + StringUtils.trimWhitespace(query);
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

    public static final class HyDEQueryPlan {
        private final String originalQuery;
        private final String vectorQuery;
        private final String keywordQuery;
        private final String rerankQuery;
        private final String hypotheticalDocument;
        private final boolean hydeApplied;
        private final boolean cacheHit;
        private final String skipReason;
        private final String queryHash;
        private final String failureReason;

        private HyDEQueryPlan(String originalQuery, String vectorQuery, String keywordQuery, String rerankQuery,
                String hypotheticalDocument, boolean hydeApplied, boolean cacheHit, String skipReason,
                String queryHash, String failureReason) {
            this.originalQuery = originalQuery;
            this.vectorQuery = vectorQuery;
            this.keywordQuery = keywordQuery;
            this.rerankQuery = rerankQuery;
            this.hypotheticalDocument = hypotheticalDocument;
            this.hydeApplied = hydeApplied;
            this.cacheHit = cacheHit;
            this.skipReason = skipReason;
            this.queryHash = queryHash;
            this.failureReason = failureReason;
        }

        public static HyDEQueryPlan applied(String originalQuery, String hypotheticalDocument, boolean cacheHit,
                String queryHash) {
            String vectorQuery = originalQuery;
            if (StringUtils.hasText(hypotheticalDocument)
                    && !originalQuery.trim().equalsIgnoreCase(hypotheticalDocument.trim())) {
                vectorQuery = originalQuery + "\n" + hypotheticalDocument;
            }
            return new HyDEQueryPlan(originalQuery, vectorQuery, originalQuery, originalQuery, hypotheticalDocument,
                    true, cacheHit, null, queryHash, null);
        }

        public static HyDEQueryPlan skip(String originalQuery, String skipReason, String queryHash) {
            return new HyDEQueryPlan(originalQuery, originalQuery, originalQuery, originalQuery, null, false, false,
                    skipReason, queryHash, null);
        }

        public static HyDEQueryPlan failed(String originalQuery, String queryHash, String failureReason) {
            return new HyDEQueryPlan(originalQuery, originalQuery, originalQuery, originalQuery, null, false, false,
                    "generation_failed", queryHash, failureReason);
        }

        public String getOriginalQuery() {
            return originalQuery;
        }

        public String getVectorQuery() {
            return vectorQuery;
        }

        public String getKeywordQuery() {
            return keywordQuery;
        }

        public String getRerankQuery() {
            return rerankQuery;
        }

        public String getHypotheticalDocument() {
            return hypotheticalDocument;
        }

        public boolean isHydeApplied() {
            return hydeApplied;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }

        public String getSkipReason() {
            return skipReason;
        }

        public String getQueryHash() {
            return queryHash;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }
}
