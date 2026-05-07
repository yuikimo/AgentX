package com.example.agentx.domain.llm.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.ApplicationEventPublisher;
import com.example.agentx.domain.llm.event.ModelCreatedEvent;
import com.example.agentx.domain.llm.event.ModelDeletedEvent;
import com.example.agentx.domain.llm.event.ModelStatusChangedEvent;
import com.example.agentx.domain.llm.event.ModelUpdatedEvent;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderAggregate;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.infrastructure.entity.Operator;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;
import com.example.agentx.domain.llm.model.enums.ProviderType;
import com.example.agentx.domain.llm.repository.ModelRepository;
import com.example.agentx.domain.llm.repository.ProviderRepository;
import com.example.agentx.domain.rag.service.RagQaDatasetDomainService;
import org.springframework.stereotype.Service;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.domain.llm.event.ModelsBatchDeletedEvent;
import com.example.agentx.domain.llm.model.enums.ModelType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.stream.Collectors;

/** LLM领域服务 负责服务提供商和模型的核心业务逻辑 */
@Service
public class LLMDomainService {

    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RagQaDatasetDomainService ragQaDatasetDomainService;
    private final Cache<String, ModelEntity> modelCache = CacheBuilder.newBuilder().maximumSize(1024)
            .expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, ProviderEntity> providerCache = CacheBuilder.newBuilder().maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, ProviderEntity> userProviderCache = CacheBuilder.newBuilder().maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(10)).build();

    public LLMDomainService(ProviderRepository providerRepository, ModelRepository modelRepository,
            ApplicationEventPublisher eventPublisher, RagQaDatasetDomainService ragQaDatasetDomainService) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.eventPublisher = eventPublisher;
        this.ragQaDatasetDomainService = ragQaDatasetDomainService;
    }

    /** 创建服务商
     * @param provider 服务商信息
     * @return 创建后的服务商ID */
    public ProviderEntity createProvider(ProviderEntity provider) {
        validateProviderProtocol(provider.getProtocol());
        providerRepository.insert(provider);
        if (provider.getId() != null) {
            providerCache.put(provider.getId(), provider);
            if (provider.getUserId() != null) {
                userProviderCache.put(buildUserProviderCacheKey(provider.getId(), provider.getUserId()), provider);
            }
        }
        return provider;
    }

    /** 更新服务商
     * @param provider 服务商信息 */
    public void updateProvider(ProviderEntity provider) {
        validateProviderProtocol(provider.getProtocol());
        LambdaUpdateWrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaUpdate()
                .eq(ProviderEntity::getId, provider.getId())
                .eq(provider.needCheckUserId(), ProviderEntity::getUserId, provider.getUserId());
        providerRepository.checkedUpdate(provider, wrapper);
        invalidateProviderCache(provider.getId(), provider.getUserId());
    }

    /** 获取用户自己的服务商
     * @param userId 用户id */
    public List<ProviderAggregate> getUserProviders(String userId) {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getUserId, userId);
        List<ProviderEntity> providers = providerRepository.selectList(wrapper);

        return buildProviderAggregatesWithActiveModels(providers);
    }

    /** 获取所有服务商（包含官方和用户自定义）
     * @param userId 用户ID
     * @return 服务商聚合根列表 */
    public List<ProviderAggregate> getAllProviders(String userId) {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getUserId, userId)
                .or().eq(ProviderEntity::getIsOfficial, true);
        List<ProviderEntity> providers = providerRepository.selectList(wrapper);

        return buildProviderAggregatesWithActiveModels(providers);
    }

    /** 获取官方服务商
     * @return 官方服务商聚合根列表 */
    public List<ProviderAggregate> getOfficialProviders() {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getIsOfficial,
                true);
        List<ProviderEntity> providers = providerRepository.selectList(wrapper);

        return buildProviderAggregatesWithActiveModels(providers);
    }

    /** 获取用户自定义服务商
     * @param userId 用户ID
     * @return 用户自定义服务商聚合根列表 */
    public List<ProviderAggregate> getCustomProviders(String userId) {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getUserId, userId)
                .eq(ProviderEntity::getIsOfficial, false);
        List<ProviderEntity> providers = providerRepository.selectList(wrapper);

        return buildProviderAggregatesWithActiveModels(providers);
    }

    /** 构建服务商聚合根，只包含激活的模型
     * @param providers 服务商列表
     * @return 服务商聚合根列表 */
    private List<ProviderAggregate> buildProviderAggregatesWithActiveModels(List<ProviderEntity> providers) {
        List<ProviderAggregate> providerAggregates = new ArrayList<>();
        if (providers == null || providers.isEmpty()) {
            return providerAggregates;
        }

        // 收集服务商id
        List<String> providerIds = providers.stream().map(ProviderEntity::getId).collect(Collectors.toList());
        // 查询激活的模型
        List<ModelEntity> activeModels = modelRepository.selectList(Wrappers.<ModelEntity>lambdaQuery()
                .in(ModelEntity::getProviderId, providerIds).eq(ModelEntity::getStatus, true));
        // 转为map，映射关系
        Map<String, List<ModelEntity>> modelMap = activeModels.stream()
                .collect(Collectors.groupingBy(ModelEntity::getProviderId));

        // 遍历服务商，创建聚合根，设置模型
        for (ProviderEntity provider : providers) {
            List<ModelEntity> modelList = modelMap.get(provider.getId());
            ProviderAggregate providerAggregate = new ProviderAggregate(provider, modelList);
            providerAggregates.add(providerAggregate);
        }
        return providerAggregates;
    }

    /** 获取服务商
     * @param providerId 服务商id
     * @param userId 用户id */
    public ProviderEntity getProvider(String providerId, String userId) {
        String cacheKey = buildUserProviderCacheKey(providerId, userId);
        ProviderEntity cached = userProviderCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getId, providerId)
                .eq(ProviderEntity::getUserId, userId);
        ProviderEntity provider = providerRepository.selectOne(wrapper);
        if (provider == null) {
            throw new BusinessException("服务商不存在");
        }
        userProviderCache.put(cacheKey, provider);
        return provider;
    }

    public ProviderEntity getProvider(String providerId) {
        ProviderEntity cached = providerCache.getIfPresent(providerId);
        if (cached != null) {
            return cached;
        }

        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getId, providerId);
        ProviderEntity provider = providerRepository.selectOne(wrapper);
        if (provider == null) {
            throw new BusinessException("服务商不存在");
        }
        providerCache.put(providerId, provider);
        return provider;
    }

    /** 查找服务商
     * @param providerId 服务商id */
    public ProviderEntity findProviderById(String providerId) {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getId, providerId);
        ProviderEntity provider = providerRepository.selectOne(wrapper);
        if (provider == null) {
            return null;
        }
        return provider;
    }

    // 检查服务商是否存在
    public void checkProviderExists(String providerId, String userId) {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getId, providerId)
                .eq(ProviderEntity::getUserId, userId);
        ProviderEntity provider = providerRepository.selectOne(wrapper);
        if (provider == null) {
            throw new BusinessException("服务商不存在");
        }
    }

    // 获取服务商聚合根
    public ProviderAggregate getProviderAggregate(String providerId, String userId) {
        // 获取服务商
        ProviderEntity provider = getProvider(providerId, userId);
        // 获取服务商下的激活模型列表
        List<ModelEntity> modelList = getActiveModelList(providerId, userId);

        return new ProviderAggregate(provider, modelList);
    }

    // 获取模型列表
    public List<ModelEntity> getModelList(String providerId, String userId) {
        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getProviderId, providerId)
                .eq(ModelEntity::getUserId, userId);
        return modelRepository.selectList(wrapper);
    }

    /** 获取激活的模型列表
     * @param providerId 服务商ID
     * @param userId 用户ID
     * @return 激活的模型列表 */
    public List<ModelEntity> getActiveModelList(String providerId, String userId) {
        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getProviderId, providerId)
                .eq(ModelEntity::getUserId, userId).eq(ModelEntity::getStatus, true);
        return modelRepository.selectList(wrapper);
    }

    /** 删除服务商
     * @param providerId 服务商id
     * @param userId 用户id */
    public void deleteProvider(String providerId, String userId, Operator operator) {
        // 删除服务商前先获取要删除的模型列表，用于发布批量删除事件
        Wrapper<ModelEntity> modelQueryWrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getProviderId,
                providerId);
        List<ModelEntity> modelsToDelete = modelRepository.selectList(modelQueryWrapper);
        validateEmbeddingModelsNotReferenced(modelsToDelete);

        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getId, providerId)
                .eq(operator.needCheckUserId(), ProviderEntity::getUserId, userId);
        providerRepository.checkedDelete(wrapper);
        invalidateProviderCache(providerId, userId);

        // 删除模型
        Wrapper<ModelEntity> modelWrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getProviderId,
                providerId);
        int delete = modelRepository.delete(modelWrapper);
        modelsToDelete.forEach(model -> modelCache.invalidate(model.getId()));

        // 如果有模型被删除，发布批量删除事件
        if (delete > 0) {
            List<ModelsBatchDeletedEvent.ModelDeleteItem> deleteItems = modelsToDelete.stream()
                    .map(model -> new ModelsBatchDeletedEvent.ModelDeleteItem(model.getId(), model.getUserId()))
                    .collect(Collectors.toList());

            eventPublisher.publishEvent(new ModelsBatchDeletedEvent(deleteItems, userId));
        }
    }

    /** 验证服务商协议是否支持
     * @param protocol 协议 */
    private void validateProviderProtocol(ProviderProtocol protocol) {
        // TODO: 从配置或枚举中获取支持的服务商协议列表
        if (!isSupportedProvider(protocol)) {
            throw new BusinessException("不支持的服务商协议类型: " + protocol);
        }
    }

    /** 检查是否是支持的服务商协议
     * @param protocol 服务商提供商编码
     * @return */
    private boolean isSupportedProvider(ProviderProtocol protocol) {
        return Arrays.stream(ProviderProtocol.values()).anyMatch(providerType -> providerType == protocol);
    }

    /** 获取所有支持的服务商协议
     * @return */
    public List<ProviderProtocol> getProviderProtocols() {
        return Arrays.asList(ProviderProtocol.values());
    }

    /** 创建模型
     * @param model 模型信息 */
    public void createModel(ModelEntity model) {
        modelRepository.insert(model);
        if (model.getId() != null) {
            modelCache.put(model.getId(), model);
        }

        // 发布模型创建事件
        eventPublisher.publishEvent(new ModelCreatedEvent(model.getId(), model.getUserId(), model));
    }

    /** 修改模型
     * @param model 模型信息 */
    public void updateModel(ModelEntity model) {
        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getId, model.getId())
                .eq(ModelEntity::getUserId, model.getUserId());
        modelRepository.checkedUpdate(model, wrapper);
        modelCache.invalidate(model.getId());

        // 发布模型更新事件
        eventPublisher.publishEvent(new ModelUpdatedEvent(model.getId(), model.getUserId(), model));
    }

    /** 删除模型
     * @param modelId 模型id */
    public void deleteModel(String modelId, String userId, Operator operator) {
        validateEmbeddingModelNotReferenced(modelId);
        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getId, modelId)
                .eq(operator.needCheckUserId(), ModelEntity::getUserId, userId);
        modelRepository.checkedDelete(wrapper);
        modelCache.invalidate(modelId);

        // 发布模型删除事件
        eventPublisher.publishEvent(new ModelDeletedEvent(modelId, userId));
    }

    /** 修改模型状态
     * @param modelId 模型id
     * @param userId 用户id */
    public void updateModelStatus(String modelId, String userId) {
        // 先获取当前模型信息，用于判断状态变更
        ModelEntity currentModel = getModelById(modelId);
        boolean currentStatus = Boolean.TRUE.equals(currentModel.getStatus());
        boolean newStatus = !currentStatus; // 状态取反
        if (!newStatus) {
            validateEmbeddingModelNotReferenced(modelId);
        }

        LambdaUpdateWrapper<ModelEntity> updateWrapper = Wrappers.lambdaUpdate(ModelEntity.class)
                .eq(ModelEntity::getId, modelId).eq(ModelEntity::getUserId, userId)
                .setSql("status = CASE WHEN status IS NULL THEN TRUE ELSE NOT status END");

        modelRepository.checkedUpdate(updateWrapper);
        modelCache.invalidate(modelId);

        // 获取更新后的模型信息
        ModelEntity updatedModel = getModelById(modelId);

        // 发布模型状态变更事件
        eventPublisher.publishEvent(new ModelStatusChangedEvent(modelId, userId, updatedModel, newStatus, ""));
    }

    private void validateEmbeddingModelNotReferenced(String modelId) {
        ModelEntity model = findModelById(modelId);
        if (model == null || model.getType() != ModelType.EMBEDDING) {
            return;
        }
        long referenceCount = ragQaDatasetDomainService.countDatasetsReferencingEmbeddingModel(modelId);
        if (referenceCount > 0) {
            throw new BusinessException(
                    "该嵌入模型正在被知识库引用（含迁移中目标模型），请先完成知识库模型迁移并清空引用后再删除/禁用");
        }
    }

    private void validateEmbeddingModelsNotReferenced(List<ModelEntity> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        for (ModelEntity model : models) {
            if (model != null && model.getType() == ModelType.EMBEDDING) {
                long referenceCount = ragQaDatasetDomainService.countDatasetsReferencingEmbeddingModel(model.getId());
                if (referenceCount > 0) {
                    throw new BusinessException("服务商下存在被知识库引用的嵌入模型，需先迁移知识库后再删除服务商");
                }
            }
        }
    }

    /** 根据类型获取服务商
     * @param providerType 服务商类型编码
     * @param userId 用户ID
     * @return 服务商聚合根列表 */
    public List<ProviderAggregate> getProvidersByType(ProviderType providerType, String userId) {
        LambdaQueryWrapper<ProviderEntity> wrapper = Wrappers.lambdaQuery();

        switch (providerType) {
            case OFFICIAL :
                wrapper.eq(ProviderEntity::getIsOfficial, true);
                break;
            case CUSTOM :
                wrapper.eq(ProviderEntity::getUserId, userId).eq(ProviderEntity::getIsOfficial, false);
                break;
            case ALL :
            default :
                wrapper.eq(ProviderEntity::getUserId, userId).or().eq(ProviderEntity::getIsOfficial, true);
        }

        return buildProviderAggregatesWithActiveModels(providerRepository.selectList(wrapper));
    }

    /** 修改服务商状态
     * @param providerId 服务商id
     * @param userId 用户id */
    public void updateProviderStatus(String providerId, String userId) {
        LambdaUpdateWrapper<ProviderEntity> updateWrapper = Wrappers.lambdaUpdate(ProviderEntity.class)
                .eq(ProviderEntity::getId, providerId).eq(ProviderEntity::getUserId, userId)
                .setSql("status = CASE WHEN status IS NULL THEN TRUE ELSE NOT status END");
        providerRepository.checkedUpdate(updateWrapper);
        invalidateProviderCache(providerId, userId);
    }

    /** 获取模型
     * @param modelId 模型id */
    public ModelEntity getModelById(String modelId) {
        ModelEntity modelEntity = modelCache.getIfPresent(modelId);
        if (modelEntity != null) {
            return modelEntity;
        }
        modelEntity = modelRepository.selectById(modelId);
        if (modelEntity == null) {
            throw new BusinessException("模型不存在");
        }
        modelCache.put(modelId, modelEntity);
        return modelEntity;
    }

    public ModelEntity findModelById(String modelId) {
        ModelEntity modelEntity = modelCache.getIfPresent(modelId);
        if (modelEntity != null) {
            return modelEntity;
        }
        modelEntity = modelRepository.selectById(modelId);
        if (modelEntity == null) {
            return null;
        }
        modelCache.put(modelId, modelEntity);
        return modelEntity;
    }

    private void invalidateProviderCache(String providerId, String userId) {
        providerCache.invalidate(providerId);
        if (userId != null) {
            userProviderCache.invalidate(buildUserProviderCacheKey(providerId, userId));
        } else {
            userProviderCache.invalidateAll();
        }
    }

    private String buildUserProviderCacheKey(String providerId, String userId) {
        return providerId + "|" + userId;
    }

    /** 获取所有激活的模型
     * @return 所有激活的模型列表 */
    public List<ModelEntity> getAllActiveModels() {
        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getStatus, true);
        return modelRepository.selectList(wrapper);
    }

    /** 批量获取模型信息
     * @param modelIds 模型ID集合
     * @return 模型列表 */
    public List<ModelEntity> getModelsByIds(Set<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return new ArrayList<>();
        }

        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().in(ModelEntity::getId, modelIds);
        return modelRepository.selectList(wrapper);
    }

    /** 获取服务商下的所有模型（包括禁用的）- 用于管理员功能
     * @param providerId 服务商ID
     * @param userId 用户ID
     * @return 所有模型列表 */
    public List<ModelEntity> getAllModelList(String providerId, String userId) {
        Wrapper<ModelEntity> wrapper = Wrappers.<ModelEntity>lambdaQuery().eq(ModelEntity::getProviderId, providerId)
                .eq(ModelEntity::getUserId, userId);
        return modelRepository.selectList(wrapper);
    }

    /** 构建包含所有模型的服务商聚合根 - 用于管理员功能
     * @param providers 服务商列表
     * @return 服务商聚合根列表 */
    private List<ProviderAggregate> buildProviderAggregatesWithAllModels(List<ProviderEntity> providers) {
        if (providers.isEmpty()) {
            return new ArrayList<>();
        }

        // 获取所有服务商ID
        List<String> providerIds = providers.stream().map(ProviderEntity::getId).collect(Collectors.toList());

        // 批量查询所有模型（不过滤状态）
        Wrapper<ModelEntity> modelWrapper = Wrappers.<ModelEntity>lambdaQuery().in(ModelEntity::getProviderId,
                providerIds);
        List<ModelEntity> allModels = modelRepository.selectList(modelWrapper);

        // 按服务商分组
        Map<String, List<ModelEntity>> modelMap = allModels.stream()
                .collect(Collectors.groupingBy(ModelEntity::getProviderId));

        // 构建聚合根
        return providers.stream().map(provider -> {
            List<ModelEntity> models = modelMap.getOrDefault(provider.getId(), new ArrayList<>());
            return new ProviderAggregate(provider, models);
        }).collect(Collectors.toList());
    }

    /** 获取官方服务商（包含所有模型）- 用于管理员功能
     * @return 官方服务商聚合根列表 */
    public List<ProviderAggregate> getOfficialProvidersWithAllModels() {
        Wrapper<ProviderEntity> wrapper = Wrappers.<ProviderEntity>lambdaQuery().eq(ProviderEntity::getIsOfficial,
                true);
        List<ProviderEntity> providers = providerRepository.selectList(wrapper);

        return buildProviderAggregatesWithAllModels(providers);
    }
}
