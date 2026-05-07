package com.example.agentx.infrastructure.rag.factory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/** 嵌入模型工厂类 根据用户配置动态创建嵌入模型实例
 * 
 * @author shilong.zang
 * @date 2025-01-22 */
@Component
public class EmbeddingModelFactory {

    private static final int EMBEDDING_MODEL_CACHE_MAX_SIZE = 128;
    private static final Duration EMBEDDING_MODEL_CACHE_TTL = Duration.ofMinutes(30);

    private final Cache<String, OpenAiEmbeddingModel> embeddingModelCache = CacheBuilder.newBuilder()
            .maximumSize(EMBEDDING_MODEL_CACHE_MAX_SIZE).expireAfterAccess(EMBEDDING_MODEL_CACHE_TTL).build();

    /** 嵌入模型配置类 */
    public static class EmbeddingConfig {
        private String apiKey;
        private String baseUrl;
        private String modelEndpoint;

        public EmbeddingConfig() {
        }

        public EmbeddingConfig(String apiKey, String baseUrl, String modelName) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.modelEndpoint = modelName;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelEndpoint() {
            return modelEndpoint;
        }

        public void setModelEndpoint(String modelEndpoint) {
            this.modelEndpoint = modelEndpoint;
        }
    }

    /** 根据配置创建OpenAI嵌入模型实例
     * 
     * @param config 嵌入模型配置
     * @return OpenAiEmbeddingModel实例 */
    public OpenAiEmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
        String cacheKey = buildCacheKey(config);
        OpenAiEmbeddingModel cached = embeddingModelCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        OpenAiEmbeddingModel created = OpenAiEmbeddingModel.builder().apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl()).modelName(config.getModelEndpoint()).build();
        embeddingModelCache.put(cacheKey, created);
        return created;
    }

    private String buildCacheKey(EmbeddingConfig config) {
        return String.join("|", safe(config.getBaseUrl()), safe(config.getModelEndpoint()), sha256(safe(config.getApiKey())));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }
}
