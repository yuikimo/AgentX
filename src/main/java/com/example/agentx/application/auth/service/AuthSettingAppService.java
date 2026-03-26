package com.example.agentx.application.auth.service;

import com.example.agentx.application.auth.assembler.AuthSettingAssembler;
import com.example.agentx.application.auth.dto.AuthConfigDTO;
import com.example.agentx.application.auth.dto.AuthSettingDTO;
import com.example.agentx.application.auth.dto.LoginMethodDTO;
import com.example.agentx.application.auth.dto.UpdateAuthSettingRequest;
import com.example.agentx.domain.auth.constant.AuthFeatureKey;
import com.example.agentx.domain.auth.constant.FeatureType;
import com.example.agentx.domain.auth.model.AuthSettingEntity;
import com.example.agentx.domain.auth.service.AuthSettingDomainService;
import com.example.agentx.domain.sso.model.SsoProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;

/**
 * 认证配置应用服务
 */
@Service
public class AuthSettingAppService {

    private final AuthSettingDomainService authSettingDomainService;

    public AuthSettingAppService(AuthSettingDomainService authSettingDomainService) {
        this.authSettingDomainService = authSettingDomainService;
    }

    /**
     * 获取前端认证配置
     *
     * @return 认证配置DTO
     */
    public AuthConfigDTO getAuthConfig() {
        // 获取启用的登录方式
        List<AuthSettingEntity> loginSettings = authSettingDomainService.getEnabledFeatures(FeatureType.LOGIN);

        HashMap<String, LoginMethodDTO> loginMethods = new HashMap<>();
        for (AuthSettingEntity setting : loginSettings) {
            LoginMethodDTO method = new LoginMethodDTO();
            method.setEnabled(setting.getEnabled());
            method.setName(setting.getFeatureName());

            // 根据功能键设置provider
            String providerCode = getProviderCodeByFeatureKey(setting.getFeatureKey());
            if (providerCode != null) {
                method.setProvider(providerCode);
            }

            loginMethods.put(setting.getFeatureKey(), method);
        }

        // 检查注册是否启用
        boolean registerEnabled = authSettingDomainService.isFeatureEnabled(AuthFeatureKey.USER_REGISTER);

        AuthConfigDTO config = new AuthConfigDTO();
        config.setLoginMethods(loginMethods);
        config.setRegisterEnabled(registerEnabled);

        return config;
    }

    /**
     * 获取所有认证配置
     *
     * @return 认证配置列表
     */
    public List<AuthSettingDTO> getAllAuthSettings() {
        List<AuthSettingEntity> entities = authSettingDomainService.getAllAuthSettings();
        return AuthSettingAssembler.toDTOs(entities);
    }

    /**
     * 根据ID获取认证配置
     *
     * @param id 配置ID
     * @return 认证配置DTO
     */
    public AuthSettingDTO getAuthSettingById(String id) {
        AuthSettingEntity entity = authSettingDomainService.getById(id);
        return AuthSettingAssembler.toDTO(entity);
    }

    /**
     * 切换认证配置启用状态
     *
     * @param id 配置ID
     * @return 更新后的配置DTO
     */
    public AuthSettingDTO toggleAuthSetting(String id) {
        AuthSettingEntity entity = authSettingDomainService.toggleEnabled(id);
        return AuthSettingAssembler.toDTO(entity);
    }

    /**
     * 更新认证配置
     *
     * @param id      配置ID
     * @param request 更新请求
     * @return 更新后的配置DTO
     */
    public AuthSettingDTO updateAuthSetting(String id, UpdateAuthSettingRequest request) {
        AuthSettingEntity entity = authSettingDomainService.getById(id);
        AuthSettingEntity updatedEntity = AuthSettingAssembler.updateEntity(entity, request);
        AuthSettingEntity savedEntity = authSettingDomainService.updateAuthSetting(updatedEntity);
        return AuthSettingAssembler.toDTO(savedEntity);
    }

    /**
     * 删除认证配置
     *
     * @param id 配置ID
     */
    public void deleteAuthSetting(String id) {
        authSettingDomainService.deleteAuthSetting(id);
    }

    /**
     * 根据认证功能键获取对应的SSO提供商代码
     *
     * @param featureKey 认证功能键
     * @return SSO提供商代码（大写）
     */
    private String getProviderCodeByFeatureKey(String featureKey) {
        if (AuthFeatureKey.GITHUB_LOGIN.getCode().equals(featureKey)) {
            return SsoProvider.GITHUB.getCode().toUpperCase();
        } else if (AuthFeatureKey.COMMUNITY_LOGIN.getCode().equals(featureKey)) {
            return SsoProvider.COMMUNITY.getCode().toUpperCase();
        }
        return null;
    }
}
