package com.example.agentx.domain.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.rag.constant.EmbeddingDistanceMetric;
import com.example.agentx.domain.rag.model.EmbeddingProfileEntity;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.domain.rag.repository.EmbeddingProfileRepository;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.rag.factory.EmbeddingModelFactory;
import com.example.agentx.infrastructure.rag.service.EmbeddingStoreRouter;
import com.example.agentx.infrastructure.rag.service.VectorTableRegistry;
import com.example.agentx.infrastructure.rag.service.UserModelConfigResolver;

/** Embedding Profile 领域服务（本地缓存 + 幂等创建） */
@Service
public class EmbeddingProfileDomainService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileDomainService.class);
    private static final String DEFAULT_DISTANCE_METRIC = EmbeddingDistanceMetric.COSINE.name();

    private final EmbeddingProfileRepository embeddingProfileRepository;
    private final UserModelConfigResolver userModelConfigResolver;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreRouter embeddingStoreRouter;
    private final VectorTableRegistry vectorTableRegistry;
    private final Map<String, EmbeddingProfileEntity> profileByIdCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingProfileEntity> profileByFingerprintCache = new ConcurrentHashMap<>();

    public EmbeddingProfileDomainService(EmbeddingProfileRepository embeddingProfileRepository,
            UserModelConfigResolver userModelConfigResolver, EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreRouter embeddingStoreRouter, VectorTableRegistry vectorTableRegistry) {
        this.embeddingProfileRepository = embeddingProfileRepository;
        this.userModelConfigResolver = userModelConfigResolver;
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreRouter = embeddingStoreRouter;
        this.vectorTableRegistry = vectorTableRegistry;
    }

    /** 按模型ID解析/创建 Profile */
    public EmbeddingProfileEntity resolveOrCreateProfile(String userId, String modelId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(modelId)) {
            throw new BusinessException("解析Embedding Profile失败，userId/modelId不能为空");
        }
        ModelConfig modelConfig = userModelConfigResolver.getModelConfigByModelId(userId, modelId, ModelType.EMBEDDING);
        return resolveOrCreateProfile(userId, modelId, modelConfig);
    }

    /** 按模型配置解析/创建 Profile */
    public EmbeddingProfileEntity resolveOrCreateProfile(String userId, String modelId, ModelConfig modelConfig) {
        int dimension = probeDimension(modelConfig);
        String fingerprint = buildFingerprint(userId, modelId, modelConfig, dimension, DEFAULT_DISTANCE_METRIC);
        String cacheKey = userId + ":" + fingerprint;

        EmbeddingProfileEntity cached = profileByFingerprintCache.get(cacheKey);
        if (cached != null) {
            embeddingStoreRouter.getOrCreateStore(cached.getId(), cached.getTableName(), cached.getDimension(),
                    EmbeddingDistanceMetric.valueOf(cached.getDistanceMetric()));
            return cached;
        }

        EmbeddingProfileEntity existing = embeddingProfileRepository.selectOne(Wrappers.<EmbeddingProfileEntity>lambdaQuery()
                .eq(EmbeddingProfileEntity::getUserId, userId)
                .eq(EmbeddingProfileEntity::getConfigFingerprint, fingerprint));
        if (existing != null) {
            cache(existing, cacheKey);
            embeddingStoreRouter.getOrCreateStore(existing.getId(), existing.getTableName(), existing.getDimension(),
                    EmbeddingDistanceMetric.valueOf(existing.getDistanceMetric()));
            return existing;
        }

        String profileId = fingerprint.substring(0, 32);
        String tableName = "public.vector_store_ep_" + profileId.substring(0, 16);

        EmbeddingProfileEntity profile = new EmbeddingProfileEntity();
        profile.setId(profileId);
        profile.setUserId(userId);
        profile.setModelId(modelId);
        profile.setModelEndpoint(modelConfig.getModelEndpoint());
        profile.setBaseUrl(modelConfig.getBaseUrl());
        profile.setDimension(dimension);
        profile.setDistanceMetric(DEFAULT_DISTANCE_METRIC);
        profile.setTableName(tableName);
        profile.setConfigFingerprint(fingerprint);
        profile.setStatus(true);
        embeddingProfileRepository.insert(profile);

        cache(profile, cacheKey);
        embeddingStoreRouter.getOrCreateStore(profile.getId(), profile.getTableName(), profile.getDimension(),
                EmbeddingDistanceMetric.valueOf(profile.getDistanceMetric()));

        log.info("创建Embedding Profile成功: userId={}, modelId={}, profileId={}, table={}, dim={}", userId, modelId,
                profileId, tableName, dimension);
        return profile;
    }

    public EmbeddingProfileEntity getProfileById(String profileId) {
        if (!StringUtils.hasText(profileId)) {
            return null;
        }
        EmbeddingProfileEntity cached = profileByIdCache.get(profileId);
        if (cached != null) {
            return cached;
        }
        EmbeddingProfileEntity profile = embeddingProfileRepository.selectById(profileId);
        if (profile != null) {
            String cacheKey = profile.getUserId() + ":" + profile.getConfigFingerprint();
            cache(profile, cacheKey);
        }
        return profile;
    }

    public List<EmbeddingProfileEntity> listAllProfiles() {
        return embeddingProfileRepository.selectList(Wrappers.<EmbeddingProfileEntity>lambdaQuery());
    }

    private int probeDimension(ModelConfig modelConfig) {
        try {
            EmbeddingModelFactory.EmbeddingConfig cfg = new EmbeddingModelFactory.EmbeddingConfig(
                    modelConfig.getApiKey(), modelConfig.getBaseUrl(), modelConfig.getModelEndpoint());
            OpenAiEmbeddingModel embeddingModel = embeddingModelFactory.createEmbeddingModel(cfg);
            float[] vector = embeddingModel.embed("dimension probe").content().vector();
            if (vector == null || vector.length <= 0) {
                throw new BusinessException("获取嵌入维度失败：返回空向量");
            }
            return vector.length;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取嵌入维度失败: " + e.getMessage(), e);
        }
    }

    private String buildFingerprint(String userId, String modelId, ModelConfig modelConfig, int dimension,
            String metric) {
        String raw = userId + "|" + modelId + "|" + modelConfig.getBaseUrl() + "|" + modelConfig.getModelEndpoint()
                + "|" + dimension + "|" + metric;
        return sha256(raw);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String part = Integer.toHexString(0xff & b);
                if (part.length() == 1) {
                    hex.append('0');
                }
                hex.append(part);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void cache(EmbeddingProfileEntity profile, String cacheKey) {
        profileByIdCache.put(profile.getId(), profile);
        profileByFingerprintCache.put(cacheKey, profile);
        vectorTableRegistry.registerTable(profile.getTableName());
    }
}
