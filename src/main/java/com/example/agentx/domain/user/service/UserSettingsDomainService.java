package com.example.agentx.domain.user.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.user.model.UserSettingsEntity;
import com.example.agentx.domain.user.model.config.FallbackConfig;
import com.example.agentx.domain.user.repository.UserSettingsRepository;

/** 用户设置领域服务 */
@Service
public class UserSettingsDomainService {

    private final UserSettingsRepository userSettingsRepository;
    private final Cache<String, UserSettingsEntity> userSettingsCache = CacheBuilder.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(5)).build();

    public UserSettingsDomainService(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    /** 获取用户设置
     * @param userId 用户ID
     * @return 用户设置实体 */
    public UserSettingsEntity getUserSettings(String userId) {
        UserSettingsEntity cached = userSettingsCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        LambdaQueryWrapper<UserSettingsEntity> wrapper = Wrappers.<UserSettingsEntity>lambdaQuery()
                .eq(UserSettingsEntity::getUserId, userId);
        UserSettingsEntity settings = userSettingsRepository.selectOne(wrapper);
        if (settings != null) {
            userSettingsCache.put(userId, settings);
        }
        return settings;
    }

    public List<UserSettingsEntity> listAllSettings() {
        return userSettingsRepository.selectList(Wrappers.<UserSettingsEntity>lambdaQuery());
    }

    /** 更新用户设置
     * @param userSettings 用户设置实体 */
    public void update(UserSettingsEntity userSettings) {
        UserSettingsEntity existingSettings = getUserSettings(userSettings.getUserId());
        if (existingSettings == null) {
            userSettingsRepository.checkInsert(userSettings);
            userSettingsCache.put(userSettings.getUserId(), userSettings);
            return;
        }

        userSettings.setId(existingSettings.getId());
        Wrapper<UserSettingsEntity> wrapper = Wrappers.<UserSettingsEntity>lambdaQuery()
                .eq(UserSettingsEntity::getUserId, userSettings.getUserId());
        userSettingsRepository.checkedUpdate(userSettings, wrapper);
        userSettingsCache.put(userSettings.getUserId(), userSettings);
    }

    /** 获取用户默认模型ID
     * @param userId 用户ID
     * @return 默认模型ID */
    public String getUserDefaultModelId(String userId) {
        UserSettingsEntity settings = getUserSettings(userId);
        return settings != null ? settings.getDefaultModelId() : null;
    }

    /** 获取用户降级链配置
     * @param userId 用户ID
     * @return 降级模型ID列表，如果未启用降级则返回null */
    public List<String> getUserFallbackChain(String userId) {
        UserSettingsEntity settings = getUserSettings(userId);
        if (settings == null || settings.getSettingConfig() == null) {
            return new ArrayList<>();
        }

        FallbackConfig fallbackConfig = settings.getSettingConfig().getFallbackConfig();
        if (fallbackConfig == null || !fallbackConfig.isEnabled() || fallbackConfig.getFallbackChain().isEmpty()) {
            return new ArrayList<>();
        }

        return fallbackConfig.getFallbackChain();
    }

    /** 设置用户默认模型ID
     * @param userId 用户ID
     * @param modelId 模型ID */
    public void setUserDefaultModelId(String userId, String modelId) {
        UserSettingsEntity settings = getUserSettings(userId);
        if (settings == null) {
            // 创建新的用户设置
            settings = new UserSettingsEntity();
            settings.setUserId(userId);
            settings.setDefaultModelId(modelId);
            userSettingsRepository.insert(settings);
            userSettingsCache.put(userId, settings);
        } else {
            // 更新现有设置
            settings.setDefaultModelId(modelId);
            Wrapper<UserSettingsEntity> wrapper = Wrappers.<UserSettingsEntity>lambdaQuery()
                    .eq(UserSettingsEntity::getUserId, userId);
            userSettingsRepository.checkedUpdate(settings, wrapper);
            userSettingsCache.put(userId, settings);
        }
    }
}
