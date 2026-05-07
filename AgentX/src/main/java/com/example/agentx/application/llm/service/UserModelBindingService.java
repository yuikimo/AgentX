package com.example.agentx.application.llm.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderAggregate;
import com.example.agentx.domain.llm.model.enums.ModelType;
import com.example.agentx.domain.llm.model.enums.ProviderType;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.domain.user.service.UserSettingsDomainService;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserModelBindingService {

    private final LLMDomainService llmDomainService;
    private final UserSettingsDomainService userSettingsDomainService;

    public UserModelBindingService(LLMDomainService llmDomainService, UserSettingsDomainService userSettingsDomainService) {
        this.llmDomainService = llmDomainService;
        this.userSettingsDomainService = userSettingsDomainService;
    }

    public String resolveAndEnsureDefaultChatModelId(String userId) {
        String currentDefaultModelId = userSettingsDomainService.getUserDefaultModelId(userId);
        if (StringUtils.hasText(currentDefaultModelId)) {
            ModelEntity currentModel = llmDomainService.findModelById(currentDefaultModelId);
            if (currentModel != null && Boolean.TRUE.equals(currentModel.getStatus()) && currentModel.isChatType()) {
                return currentDefaultModelId;
            }
        }

        String replacementModelId = resolveReplacementModelId(userId, ModelType.CHAT, Set.of());
        if (StringUtils.hasText(replacementModelId)) {
            userSettingsDomainService.setUserDefaultModelId(userId, replacementModelId);
        }
        return replacementModelId;
    }

    public String resolveReplacementModelId(String userId, ModelType preferredType, Set<String> excludedModelIds) {
        Set<String> excluded = excludedModelIds == null ? Set.of() : excludedModelIds;

        List<ModelEntity> candidateModels = llmDomainService.getProvidersByType(ProviderType.ALL, userId).stream()
                .filter(provider -> Boolean.TRUE.equals(provider.getStatus()))
                .flatMap(provider -> provider.getModels().stream())
                .filter(model -> Boolean.TRUE.equals(model.getStatus()))
                .filter(model -> !excluded.contains(model.getId()))
                .collect(Collectors.toList());

        if (preferredType != null) {
            List<ModelEntity> typedCandidates = candidateModels.stream()
                    .filter(model -> preferredType.equals(model.getType()))
                    .collect(Collectors.toList());
            if (!typedCandidates.isEmpty()) {
                return pickBestModelId(userId, typedCandidates);
            }
            return null;
        }

        if (candidateModels.isEmpty()) {
            return null;
        }
        return pickBestModelId(userId, candidateModels);
    }

    private String pickBestModelId(String userId, List<ModelEntity> candidates) {
        return candidates.stream().sorted(Comparator
                .comparing((ModelEntity model) -> !userId.equals(model.getUserId()))
                .thenComparing(ModelEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ModelEntity::getId).findFirst().orElse(null);
    }
}
