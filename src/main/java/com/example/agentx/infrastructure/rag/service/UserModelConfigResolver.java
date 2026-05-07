package com.example.agentx.infrastructure.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.application.llm.service.UserModelBindingService;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.conversation.model.SessionEntity;
import com.example.agentx.domain.conversation.service.SessionDomainService;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.rag.model.ModelConfig;
import com.example.agentx.domain.user.model.UserSettingsEntity;
import com.example.agentx.domain.user.service.UserSettingsDomainService;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.LLMProviderService;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import dev.langchain4j.model.chat.ChatModel;

import java.util.Objects;

/** 用户模型配置解析器 - Infrastructure层服务
 *
 * 解决Domain层需要获取用户模型配置的问题
 *
 * @author shilong.zang */
@Service
public class UserModelConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(UserModelConfigResolver.class);

    private final UserSettingsDomainService userSettingsDomainService;

    private final LLMDomainService llmDomainService;

    private final SessionDomainService sessionDomainService;

    private final AgentWorkspaceDomainService agentWorkspaceDomainService;

    private final UserModelBindingService userModelBindingService;

    public UserModelConfigResolver(UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            SessionDomainService sessionDomainService, AgentWorkspaceDomainService agentWorkspaceDomainService,
            UserModelBindingService userModelBindingService) {
        this.userSettingsDomainService = userSettingsDomainService;
        this.llmDomainService = llmDomainService;
        this.sessionDomainService = sessionDomainService;
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.userModelBindingService = userModelBindingService;
    }

    /** 获取用户的嵌入模型配置
     *
     * @param userId 用户ID
     * @return 嵌入模型配置
     * @throws BusinessException 如果用户未配置嵌入模型或配置无效 */
    public ModelConfig getUserEmbeddingModelConfig(String userId) {
        try {
            UserSettingsEntity userSettings = userSettingsDomainService.getUserSettings(userId);

            // 检查用户是否配置了嵌入模型
            if (userSettings == null || userSettings.getSettingConfig() == null
                    || userSettings.getSettingConfig().getDefaultEmbeddingModel() == null) {
                String errorMsg = String.format("用户 %s 未配置默认嵌入模型，无法进行向量化处理", userId);
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }

            String modelId = userSettings.getSettingConfig().getDefaultEmbeddingModel();
            log.info("获取用户{}的嵌入模型配置，模型ID: {}", userId, modelId);

            // 根据模型ID从数据库获取真实的模型配置
            return getModelConfigFromDatabase(modelId, userId);

        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            String errorMsg = String.format("用户 %s 获取嵌入模型配置失败: %s", userId, e.getMessage());
            log.error(errorMsg, e);
            throw new BusinessException(errorMsg, e);
        }
    }

    /** 获取用户的聊天模型配置
     *
     * @param userId 用户ID
     * @return 聊天模型配置
     * @throws BusinessException 如果用户未配置聊天模型或配置无效 */
    public ModelConfig getUserChatModelConfig(String userId) {
        try {
            UserSettingsEntity userSettings = userSettingsDomainService.getUserSettings(userId);

            // 检查用户是否配置了聊天模型
            if (userSettings == null || userSettings.getSettingConfig() == null
                    || userSettings.getSettingConfig().getDefaultModel() == null) {
                String errorMsg = String.format("用户 %s 未配置默认聊天模型，无法进行LLM处理", userId);
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }

            String modelId = userSettings.getSettingConfig().getDefaultModel();
            log.info("获取用户{}的聊天模型配置，模型ID: {}", userId, modelId);

            // 根据模型ID从数据库获取真实的模型配置
            return getModelConfigFromDatabase(modelId, userId);

        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            String errorMsg = String.format("用户 %s 获取聊天模型配置失败: %s", userId, e.getMessage());
            log.error(errorMsg, e);
            throw new BusinessException(errorMsg, e);
        }
    }

    /** 获取优先模型配置（会话/工作区优先，兜底用户默认聊天模型）
     *
     * @param userId 用户ID
     * @param sessionId 会话ID（可空）
     * @return 聊天模型配置 */
    public ModelConfig getPreferredChatModelConfig(String userId, String sessionId) {
        String modelId = resolvePreferredChatModelId(userId, sessionId);
        return getModelConfigByModelId(userId, modelId, ModelType.CHAT);
    }

    /** 解析优先聊天模型ID（会话/工作区优先，兜底用户默认聊天模型）
     *
     * @param userId 用户ID
     * @param sessionId 会话ID（可空）
     * @return 模型ID */
    public String resolvePreferredChatModelId(String userId, String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            try {
                SessionEntity session = sessionDomainService.find(sessionId, userId);
                if (session != null && StringUtils.hasText(session.getAgentId())) {
                    AgentWorkspaceEntity workspace = agentWorkspaceDomainService.findWorkspace(session.getAgentId(), userId);
                    if (workspace != null && workspace.getLlmModelConfig() != null
                            && StringUtils.hasText(workspace.getLlmModelConfig().getModelId())) {
                        String workspaceModelId = workspace.getLlmModelConfig().getModelId();
                        ModelEntity workspaceModel = llmDomainService.findModelById(workspaceModelId);
                        if (workspaceModel != null && Boolean.TRUE.equals(workspaceModel.getStatus())
                                && workspaceModel.isChatType()) {
                            log.debug("优先使用会话绑定工作区模型: userId={}, sessionId={}, modelId={}", userId, sessionId,
                                    workspaceModelId);
                            return workspaceModelId;
                        }
                        log.warn("会话绑定工作区模型不可用，回退默认模型: userId={}, sessionId={}, modelId={}", userId, sessionId,
                                workspaceModelId);
                    }
                }
            } catch (Exception e) {
                log.warn("解析会话绑定模型失败，回退默认模型: userId={}, sessionId={}, err={}", userId, sessionId, e.getMessage());
            }
        }

        String defaultModelId = userModelBindingService.resolveAndEnsureDefaultChatModelId(userId);
        if (!StringUtils.hasText(defaultModelId)) {
            String errorMsg = String.format("用户 %s 未配置默认聊天模型，无法进行LLM处理", userId);
            log.error(errorMsg);
            throw new BusinessException(errorMsg);
        }
        return defaultModelId;
    }

    /** 获取用户的OCR模型配置（可用作视觉模型）
     *
     * @param userId 用户ID
     * @return OCR/视觉模型配置
     * @throws BusinessException 如果用户未配置OCR模型或配置无效 */
    public ModelConfig getUserOcrModelConfig(String userId) {
        try {
            UserSettingsEntity userSettings = userSettingsDomainService.getUserSettings(userId);

            // 检查用户是否配置了OCR模型
            if (userSettings == null || userSettings.getSettingConfig() == null
                    || userSettings.getSettingConfig().getDefaultOcrModel() == null) {
                String errorMsg = String.format("用户 %s 未配置默认OCR模型，无法进行视觉处理", userId);
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }

            String modelId = userSettings.getSettingConfig().getDefaultOcrModel();
            log.info("获取用户{}OCR模型配置，模型ID: {}", userId, modelId);

            // 根据模型ID从数据库获取真实的模型配置
            ModelConfig modelConfig = getModelConfigFromDatabase(modelId, userId);
            if (modelConfig.getModelType() != ModelType.OCR) {
                String errorMsg = String.format("用户 %s 配置的OCR模型 %s 类型不支持，当前类型: %s", userId, modelId,
                        modelConfig.getModelType());
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }
            return modelConfig;

        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            String errorMsg = String.format("用户 %s 获取OCR模型配置失败: %s", userId, e.getMessage());
            log.error(errorMsg, e);
            throw new BusinessException(errorMsg, e);
        }
    }

    /** 按指定模型ID获取模型配置
     *
     * @param userId 用户ID
     * @param modelId 模型ID
     * @param expectedType 期望模型类型，可空
     * @return 模型配置 */
    public ModelConfig getModelConfigByModelId(String userId, String modelId, ModelType expectedType) {
        if (!StringUtils.hasText(modelId)) {
            throw new BusinessException("模型ID不能为空");
        }
        ModelConfig modelConfig = getModelConfigFromDatabase(modelId, userId);
        if (expectedType != null && modelConfig.getModelType() != expectedType) {
            throw new BusinessException(String.format("模型 %s 类型不匹配，期望: %s，实际: %s", modelId, expectedType,
                    modelConfig.getModelType()));
        }
        return modelConfig;
    }

    public ModelConfig getModelConfigByModelId(String userId, String modelId) {
        return getModelConfigByModelId(userId, modelId, null);
    }

    /** 从数据库获取模型配置
     *
     * @param modelId 模型ID
     * @param userId 用户ID
     * @return 模型配置
     * @throws BusinessException 如果模型不存在或配置无效 */
    private ModelConfig getModelConfigFromDatabase(String modelId, String userId) {
        try {
            // 获取模型实体
            ModelEntity modelEntity = llmDomainService.findModelById(modelId);
            if (modelEntity == null) {
                String errorMsg = String.format("用户 %s 配置的模型 %s 不存在", userId, modelId);
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }

            // 检查模型是否激活
            if (!Boolean.TRUE.equals(modelEntity.getStatus())) {
                String errorMsg = String.format("用户 %s 配置的模型 %s 已禁用", userId, modelId);
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }

            // 获取服务商配置
            ProviderEntity providerEntity = llmDomainService.getProvider(modelEntity.getProviderId());

            // 检查服务商是否激活
            if (!Boolean.TRUE.equals(providerEntity.getStatus())) {
                String errorMsg = String.format("用户 %s 的模型 %s 关联的服务商已禁用", userId, modelId);
                log.error(errorMsg);
                throw new BusinessException(errorMsg);
            }

            providerEntity.isAvailable(providerEntity.getUserId());

            // 构建模型配置
            ModelConfig modelConfig = new ModelConfig(providerEntity.getConfig().getApiKey(),
                    providerEntity.getConfig().getBaseUrl(), modelEntity.getType(), providerEntity.getProtocol(),
                    modelEntity.getModelEndpoint());

            log.info("成功获取用户{}的模型配置: modelId={}, baseUrl={}", userId, modelEntity.getModelId(),
                    providerEntity.getConfig().getBaseUrl());

            return modelConfig;

        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            String errorMsg = String.format("用户 %s 获取模型 %s 配置时发生错误: %s", userId, modelId, e.getMessage());
            log.error(errorMsg, e);
            throw new BusinessException(errorMsg, e);
        }
    }
}
