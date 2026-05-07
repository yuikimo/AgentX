package com.example.agentx.application.user.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import com.example.agentx.application.user.assembler.UserSettingsAssembler;
import com.example.agentx.application.user.dto.UserSettingsDTO;
import com.example.agentx.application.rag.service.search.RAGSearchAppService;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.model.UserSettingsEntity;
import com.example.agentx.domain.user.model.config.FallbackConfig;
import com.example.agentx.domain.user.model.config.UserSettingsConfig;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.interfaces.dto.user.request.UserSettingsUpdateRequest;

/** 用户设置应用服务 */
@Service
public class UserSettingsAppService {

    private final UserSettingsDomainService userSettingsDomainService;
    private final LLMDomainService llmDomainService;
    private final RAGSearchAppService ragSearchAppService;

    public UserSettingsAppService(UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            RAGSearchAppService ragSearchAppService) {
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
        this.ragSearchAppService = ragSearchAppService;
    }

    /** 获取用户设置
     * @param userId 用户ID
     * @return 用户设置DTO */
    public UserSettingsDTO getUserSettings(String userId) {
        UserSettingsEntity entity = userSettingsDomainService.getUserSettings(userId);
        return UserSettingsAssembler.toDTO(entity);
    }

    /** 更新用户设置
     * @param request 更新请求
     * @param userId 用户ID
     * @return 更新后的用户设置DTO */
    public UserSettingsDTO updateUserSettings(UserSettingsUpdateRequest request, String userId) {
        UserSettingsEntity existingSettings = userSettingsDomainService.getUserSettings(userId);
        UserSettingsEntity entity = existingSettings != null ? existingSettings : new UserSettingsEntity();
        entity.setUserId(userId);
        UserSettingsConfig mergedConfig = mergeSettingConfig(existingSettings != null ? existingSettings.getSettingConfig() : null,
                request != null ? request.getSettingConfig() : null);
        validateSettingConfig(mergedConfig, userId);
        entity.setSettingConfig(mergedConfig);
        userSettingsDomainService.update(entity);
        ragSearchAppService.invalidateUserChatModelConfigCache(userId);

        return UserSettingsAssembler.toDTO(userSettingsDomainService.getUserSettings(userId));
    }

    /** 获取用户默认模型ID
     * @param userId 用户ID
     * @return 默认模型ID */
    public String getUserDefaultModelId(String userId) {
        return userSettingsDomainService.getUserDefaultModelId(userId);
    }

    /** 获取用户降级链配置
     * @param userId 用户ID
     * @return 降级模型ID列表，如果未启用降级则返回null */
    public List<String> getUserFallbackChain(String userId) {
        UserSettingsEntity settings = userSettingsDomainService.getUserSettings(userId);
        if (settings == null || settings.getSettingConfig() == null) {
            return null;
        }

        FallbackConfig fallbackConfig = settings.getSettingConfig().getFallbackConfig();
        if (fallbackConfig == null || !fallbackConfig.isEnabled() || fallbackConfig.getFallbackChain().isEmpty()) {
            return null;
        }

        return fallbackConfig.getFallbackChain();
    }

    private UserSettingsConfig mergeSettingConfig(UserSettingsConfig existingConfig, UserSettingsConfig incomingConfig) {
        UserSettingsConfig merged = existingConfig != null ? existingConfig : new UserSettingsConfig();
        if (incomingConfig == null) {
            return merged;
        }

        if (incomingConfig.getDefaultModel() != null) {
            merged.setDefaultModel(incomingConfig.getDefaultModel());
        }
        if (incomingConfig.getDefaultOcrModel() != null) {
            merged.setDefaultOcrModel(incomingConfig.getDefaultOcrModel());
        }
        if (incomingConfig.getDefaultEmbeddingModel() != null) {
            merged.setDefaultEmbeddingModel(incomingConfig.getDefaultEmbeddingModel());
        }
        if (incomingConfig.getFallbackConfig() != null) {
            merged.setFallbackConfig(incomingConfig.getFallbackConfig());
        }

        return merged;
    }

    private void validateSettingConfig(UserSettingsConfig settingConfig, String userId) {
        if (settingConfig == null) {
            return;
        }

        validateModelReference(settingConfig.getDefaultModel(), userId, "默认聊天模型", Set.of(ModelType.CHAT));
        validateModelReference(settingConfig.getDefaultOcrModel(), userId, "默认OCR模型", Set.of(ModelType.OCR));
        validateModelReference(settingConfig.getDefaultEmbeddingModel(), userId, "默认嵌入模型",
                Set.of(ModelType.EMBEDDING));

        FallbackConfig fallbackConfig = settingConfig.getFallbackConfig();
        if (fallbackConfig == null || !fallbackConfig.isEnabled() || fallbackConfig.getFallbackChain() == null) {
            return;
        }
        for (String fallbackModelId : fallbackConfig.getFallbackChain()) {
            validateModelReference(fallbackModelId, userId, "降级链模型", Set.of(ModelType.CHAT));
        }
    }

    private void validateModelReference(String modelId, String userId, String fieldName, Set<ModelType> allowedTypes) {
        if (!StringUtils.hasText(modelId)) {
            return;
        }

        ModelEntity model = llmDomainService.findModelById(modelId);
        if (model == null) {
            throw new BusinessException(fieldName + "不存在: " + modelId);
        }
        if (!Boolean.TRUE.equals(model.getStatus())) {
            throw new BusinessException(fieldName + "已禁用: " + modelId);
        }
        if (!Boolean.TRUE.equals(model.getOfficial()) && !userId.equals(model.getUserId())) {
            throw new BusinessException(fieldName + "无权限使用: " + modelId);
        }
        if (allowedTypes != null && !allowedTypes.contains(model.getType())) {
            throw new BusinessException(fieldName + "类型不匹配: " + model.getType());
        }

        ProviderEntity provider = llmDomainService.findProviderById(model.getProviderId());
        if (provider == null) {
            throw new BusinessException(fieldName + "关联服务商不存在: " + modelId);
        }
        if (!Boolean.TRUE.equals(provider.getStatus())) {
            throw new BusinessException(fieldName + "关联服务商已禁用: " + modelId);
        }
        if (!Boolean.TRUE.equals(provider.getIsOfficial()) && !userId.equals(provider.getUserId())) {
            throw new BusinessException(fieldName + "关联服务商无权限使用: " + modelId);
        }
    }
}
