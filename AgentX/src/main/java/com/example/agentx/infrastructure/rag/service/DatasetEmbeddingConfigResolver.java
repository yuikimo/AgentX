package com.example.agentx.infrastructure.rag.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.rag.model.EmbeddingProfileEntity;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.domain.rag.model.RagQaDatasetEntity;
import com.example.agentx.domain.rag.service.EmbeddingProfileDomainService;
import com.example.agentx.domain.rag.service.RagQaDatasetDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;

/** 解析数据集当前生效的嵌入模型与Profile */
@Service
public class DatasetEmbeddingConfigResolver {

    private final RagQaDatasetDomainService ragQaDatasetDomainService;
    private final EmbeddingProfileDomainService embeddingProfileDomainService;
    private final UserSettingsDomainService userSettingsDomainService;
    private final UserModelConfigResolver userModelConfigResolver;

    public DatasetEmbeddingConfigResolver(RagQaDatasetDomainService ragQaDatasetDomainService,
            EmbeddingProfileDomainService embeddingProfileDomainService, UserSettingsDomainService userSettingsDomainService,
            UserModelConfigResolver userModelConfigResolver) {
        this.ragQaDatasetDomainService = ragQaDatasetDomainService;
        this.embeddingProfileDomainService = embeddingProfileDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
        this.userModelConfigResolver = userModelConfigResolver;
    }

    public DatasetEmbeddingContext resolveActive(String datasetId, String userId) {
        RagQaDatasetEntity dataset = ragQaDatasetDomainService.getDataset(datasetId, userId);
        String modelId = resolveEmbeddingModelId(dataset.getEmbeddingModelId(), userId);
        ModelConfig modelConfig = userModelConfigResolver.getModelConfigByModelId(userId, modelId, ModelType.EMBEDDING);

        EmbeddingProfileEntity profile = resolveProfile(dataset, userId, modelId, modelConfig);
        return new DatasetEmbeddingContext(modelId, profile.getId(), profile.getTableName(), profile.getDimension(),
                modelConfig);
    }

    public DatasetEmbeddingContext resolveByDatasetEntity(RagQaDatasetEntity dataset, String userId) {
        String modelId = resolveEmbeddingModelId(dataset.getEmbeddingModelId(), userId);
        ModelConfig modelConfig = userModelConfigResolver.getModelConfigByModelId(userId, modelId, ModelType.EMBEDDING);
        EmbeddingProfileEntity profile = resolveProfile(dataset, userId, modelId, modelConfig);
        return new DatasetEmbeddingContext(modelId, profile.getId(), profile.getTableName(), profile.getDimension(),
                modelConfig);
    }

    private EmbeddingProfileEntity resolveProfile(RagQaDatasetEntity dataset, String userId, String modelId,
            ModelConfig modelConfig) {
        String profileId = dataset.getActiveEmbeddingProfileId();
        if (StringUtils.hasText(profileId)) {
            EmbeddingProfileEntity existing = embeddingProfileDomainService.getProfileById(profileId);
            if (existing != null) {
                return existing;
            }
        }

        EmbeddingProfileEntity profile = embeddingProfileDomainService.resolveOrCreateProfile(userId, modelId, modelConfig);
        ragQaDatasetDomainService.bindInitialEmbeddingProfile(dataset.getId(), userId, modelId, profile.getId());
        return profile;
    }

    private String resolveEmbeddingModelId(String preferredModelId, String userId) {
        if (StringUtils.hasText(preferredModelId)) {
            return preferredModelId;
        }
        var userSettings = userSettingsDomainService.getUserSettings(userId);
        if (userSettings == null || userSettings.getSettingConfig() == null
                || !StringUtils.hasText(userSettings.getSettingConfig().getDefaultEmbeddingModel())) {
            throw new BusinessException("未配置数据集嵌入模型，且用户通用默认嵌入模型不存在");
        }
        return userSettings.getSettingConfig().getDefaultEmbeddingModel();
    }

    public record DatasetEmbeddingContext(String modelId, String profileId, String tableName, Integer dimension,
            ModelConfig modelConfig) {
    }
}

