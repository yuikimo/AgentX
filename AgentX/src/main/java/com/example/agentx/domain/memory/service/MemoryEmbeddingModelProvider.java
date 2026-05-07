package com.example.agentx.domain.memory.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static com.example.agentx.domain.memory.service.MemoryServiceSupport.safeCacheKeyPart;
import static com.example.agentx.domain.memory.service.MemoryServiceSupport.sha256;

@Service
public class MemoryEmbeddingModelProvider {

    private static final int EMBEDDING_MODEL_CACHE_MAX_SIZE = 5000;
    private static final Duration EMBEDDING_MODEL_CACHE_TTL = Duration.ofMinutes(5);

    private final EmbeddingModelFactory embeddingModelFactory;
    private final UserModelConfigResolver userModelConfigResolver;
    private final Cache<String, EmbeddingModel> embeddingModelCache;

    public MemoryEmbeddingModelProvider(EmbeddingModelFactory embeddingModelFactory,
            UserModelConfigResolver userModelConfigResolver) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.userModelConfigResolver = userModelConfigResolver;
        this.embeddingModelCache = CacheBuilder.newBuilder().maximumSize(EMBEDDING_MODEL_CACHE_MAX_SIZE)
                .expireAfterAccess(EMBEDDING_MODEL_CACHE_TTL).build();
    }

    public EmbeddingModel resolveEmbeddingModel(String userId) {
        ModelConfig embeddingConfig = userModelConfigResolver.getUserEmbeddingModelConfig(userId);
        String cacheKey = buildEmbeddingModelCacheKey(userId, embeddingConfig);
        try {
            return embeddingModelCache.get(cacheKey,
                    () -> embeddingModelFactory.createEmbeddingModel(new EmbeddingModelFactory.EmbeddingConfig(
                            embeddingConfig.getApiKey(), embeddingConfig.getBaseUrl(),
                            embeddingConfig.getModelEndpoint())));
        } catch (ExecutionException e) {
            throw new BusinessException("创建记忆嵌入模型失败: " + e.getMessage(), e);
        }
    }

    private String buildEmbeddingModelCacheKey(String userId, ModelConfig embeddingConfig) {
        return String.join(":", safeCacheKeyPart(userId),
                safeCacheKeyPart(embeddingConfig == null ? null : embeddingConfig.getBaseUrl()),
                safeCacheKeyPart(embeddingConfig == null ? null : embeddingConfig.getModelEndpoint()),
                sha256(safeCacheKeyPart(embeddingConfig == null ? null : embeddingConfig.getApiKey())));
    }
}
