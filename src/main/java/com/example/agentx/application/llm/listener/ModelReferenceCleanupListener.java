package com.example.agentx.application.llm.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.example.agentx.application.llm.service.UserModelBindingService;
import com.example.agentx.domain.agent.model.AgentWorkspaceEntity;
import com.example.agentx.domain.agent.model.LLMModelConfig;
import com.example.agentx.domain.agent.service.AgentWorkspaceDomainService;
import com.example.agentx.domain.llm.event.ModelDeletedEvent;
import com.example.agentx.domain.llm.event.ModelStatusChangedEvent;
import com.example.agentx.domain.llm.event.ModelsBatchDeletedEvent;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.user.model.UserSettingsEntity;
import com.example.agentx.domain.user.model.config.FallbackConfig;
import com.example.agentx.domain.user.model.config.UserSettingsConfig;
import com.example.agentx.domain.user.service.UserSettingsDomainService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ModelReferenceCleanupListener {

    private static final Logger logger = LoggerFactory.getLogger(ModelReferenceCleanupListener.class);

    private final UserSettingsDomainService userSettingsDomainService;
    private final AgentWorkspaceDomainService agentWorkspaceDomainService;
    private final UserModelBindingService userModelBindingService;

    public ModelReferenceCleanupListener(UserSettingsDomainService userSettingsDomainService,
            AgentWorkspaceDomainService agentWorkspaceDomainService, UserModelBindingService userModelBindingService) {
        this.userSettingsDomainService = userSettingsDomainService;
        this.agentWorkspaceDomainService = agentWorkspaceDomainService;
        this.userModelBindingService = userModelBindingService;
    }

    @EventListener
    public void handleModelDeleted(ModelDeletedEvent event) {
        if (!StringUtils.hasText(event.getModelId())) {
            return;
        }
        repairReferences(Set.of(event.getModelId()));
    }

    @EventListener
    public void handleModelStatusChanged(ModelStatusChangedEvent event) {
        if (event.isEnabled() || !StringUtils.hasText(event.getModelId())) {
            return;
        }
        repairReferences(Set.of(event.getModelId()));
    }

    @EventListener
    public void handleModelsBatchDeleted(ModelsBatchDeletedEvent event) {
        if (event.getDeleteItems() == null || event.getDeleteItems().isEmpty()) {
            return;
        }
        Set<String> modelIds = event.getDeleteItems().stream().map(ModelsBatchDeletedEvent.ModelDeleteItem::getModelId)
                .filter(StringUtils::hasText).collect(Collectors.toSet());
        if (modelIds.isEmpty()) {
            return;
        }
        repairReferences(modelIds);
    }

    private void repairReferences(Set<String> affectedModelIds) {
        repairUserSettings(affectedModelIds);
        repairAgentWorkspaceBindings(affectedModelIds);
    }

    private void repairUserSettings(Set<String> affectedModelIds) {
        List<UserSettingsEntity> allSettings = userSettingsDomainService.listAllSettings();
        for (UserSettingsEntity settings : allSettings) {
            UserSettingsConfig settingConfig = settings.getSettingConfig();
            if (settingConfig == null) {
                continue;
            }

            String userId = settings.getUserId();
            boolean changed = false;

            String defaultModelId = settingConfig.getDefaultModel();
            if (isAffected(defaultModelId, affectedModelIds)) {
                String replacement = userModelBindingService.resolveReplacementModelId(userId, ModelType.CHAT,
                        affectedModelIds);
                settingConfig.setDefaultModel(replacement);
                changed = true;
                logger.info("修复用户默认聊天模型引用: userId={}, oldModelId={}, newModelId={}", userId, defaultModelId,
                        replacement);
            }

            String defaultOcrModelId = settingConfig.getDefaultOcrModel();
            if (isAffected(defaultOcrModelId, affectedModelIds)) {
                String replacement = userModelBindingService.resolveReplacementModelId(userId, ModelType.OCR,
                        affectedModelIds);
                settingConfig.setDefaultOcrModel(replacement);
                changed = true;
                logger.info("修复用户默认OCR模型引用: userId={}, oldModelId={}, newModelId={}", userId, defaultOcrModelId,
                        replacement);
            }

            String defaultEmbeddingModelId = settingConfig.getDefaultEmbeddingModel();
            if (isAffected(defaultEmbeddingModelId, affectedModelIds)) {
                String replacement = userModelBindingService.resolveReplacementModelId(userId, ModelType.EMBEDDING,
                        affectedModelIds);
                settingConfig.setDefaultEmbeddingModel(replacement);
                changed = true;
                logger.info("修复用户默认嵌入模型引用: userId={}, oldModelId={}, newModelId={}", userId,
                        defaultEmbeddingModelId, replacement);
            }

            FallbackConfig fallbackConfig = settingConfig.getFallbackConfig();
            if (fallbackConfig != null && fallbackConfig.getFallbackChain() != null
                    && !fallbackConfig.getFallbackChain().isEmpty()) {
                List<String> originalChain = fallbackConfig.getFallbackChain();
                List<String> filteredChain = originalChain.stream().filter(modelId -> !affectedModelIds.contains(modelId))
                        .collect(Collectors.toCollection(ArrayList::new));
                List<String> deduplicatedChain = new ArrayList<>(new LinkedHashSet<>(filteredChain));
                if (deduplicatedChain.size() != originalChain.size()) {
                    fallbackConfig.setFallbackChain(deduplicatedChain);
                    if (deduplicatedChain.isEmpty()) {
                        fallbackConfig.setEnabled(false);
                    }
                    changed = true;
                    logger.info("修复用户降级链引用: userId={}, oldSize={}, newSize={}", userId, originalChain.size(),
                            deduplicatedChain.size());
                }
            }

            if (changed) {
                settings.setSettingConfig(settingConfig);
                userSettingsDomainService.update(settings);
            }
        }
    }

    private void repairAgentWorkspaceBindings(Set<String> affectedModelIds) {
        List<AgentWorkspaceEntity> workspaces = agentWorkspaceDomainService.listAll();
        for (AgentWorkspaceEntity workspace : workspaces) {
            String modelId = workspace.getLlmModelConfig().getModelId();
            if (!isAffected(modelId, affectedModelIds)) {
                continue;
            }

            String replacement = userModelBindingService.resolveReplacementModelId(workspace.getUserId(),
                    ModelType.CHAT, affectedModelIds);
            LLMModelConfig llmModelConfig = workspace.getLlmModelConfig();
            llmModelConfig.setModelId(replacement);
            agentWorkspaceDomainService
                    .update(new AgentWorkspaceEntity(workspace.getAgentId(), workspace.getUserId(), llmModelConfig));
            logger.info("修复工作区助理模型引用: userId={}, agentId={}, oldModelId={}, newModelId={}", workspace.getUserId(),
                    workspace.getAgentId(), modelId, replacement);
        }
    }

    private boolean isAffected(String modelId, Set<String> affectedModelIds) {
        return StringUtils.hasText(modelId) && affectedModelIds.contains(modelId);
    }
}
