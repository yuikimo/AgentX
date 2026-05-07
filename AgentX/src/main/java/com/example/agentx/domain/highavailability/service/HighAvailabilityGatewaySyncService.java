package com.example.agentx.domain.highavailability.service;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.highavailability.gateway.HighAvailabilityGateway;
import com.example.agentx.domain.llm.event.ModelsBatchDeletedEvent;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.infrastructure.config.HighAvailabilityProperties;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.highavailability.dto.request.ApiInstanceBatchDeleteRequest;
import com.example.agentx.infrastructure.highavailability.dto.request.ApiInstanceCreateRequest;
import com.example.agentx.infrastructure.highavailability.dto.request.ApiInstanceUpdateRequest;
import com.example.agentx.infrastructure.highavailability.dto.request.ProjectCreateRequest;

@Service
public class HighAvailabilityGatewaySyncService {

    private static final Logger logger = LoggerFactory.getLogger(HighAvailabilityGatewaySyncService.class);

    private final HighAvailabilityProperties properties;
    private final HighAvailabilityGateway gateway;
    private final LLMDomainService llmDomainService;

    public HighAvailabilityGatewaySyncService(HighAvailabilityProperties properties, HighAvailabilityGateway gateway,
            LLMDomainService llmDomainService) {
        this.properties = properties;
        this.gateway = gateway;
        this.llmDomainService = llmDomainService;
    }

    public void syncModelToGateway(ModelEntity model) {
        if (!properties.isEnabled()) {
            logger.debug("高可用功能未启用，跳过模型同步: {}", model.getId());
            return;
        }
        try {
            gateway.createApiInstance(new ApiInstanceCreateRequest(model.getUserId(), model.getModelId(), "MODEL",
                    model.getId()));
            logger.info("成功同步模型到高可用网关: modelId={}", model.getId());
        } catch (Exception e) {
            logger.error("同步模型到高可用网关失败: modelId={}", model.getId(), e);
            throw new BusinessException("同步模型到高可用网关失败", e);
        }
    }

    public void removeModelFromGateway(String modelId) {
        if (!properties.isEnabled()) {
            logger.debug("高可用功能未启用，跳过模型删除: {}", modelId);
            return;
        }
        try {
            gateway.deleteApiInstance("MODEL", modelId);
            logger.info("成功从高可用网关删除模型: modelId={}", modelId);
        } catch (Exception e) {
            logger.error("从高可用网关删除模型失败: modelId={}", modelId, e);
        }
    }

    public void updateModelInGateway(ModelEntity model) {
        if (!properties.isEnabled()) {
            logger.debug("高可用功能未启用，跳过模型更新: {}", model.getId());
            return;
        }
        try {
            gateway.updateApiInstance("MODEL", model.getId(),
                    new ApiInstanceUpdateRequest(model.getUserId(), model.getModelId(), null, null));
            logger.info("成功更新高可用网关中的模型: modelId={}", model.getId());
        } catch (Exception e) {
            logger.error("更新高可用网关中的模型失败: modelId={}", model.getId(), e);
        }
    }

    public void initializeProject() {
        if (!properties.isEnabled()) {
            logger.info("高可用功能未启用，跳过项目初始化");
            return;
        }
        try {
            gateway.createProject(new ProjectCreateRequest("AgentX", "AgentX高可用项目", properties.getApiKey()));
            logger.info("高可用项目初始化成功");
        } catch (Exception e) {
            logger.error("高可用项目初始化失败", e);
        }
    }

    public void syncAllModelsToGateway() {
        if (!properties.isEnabled()) {
            logger.info("高可用功能未启用，跳过模型批量同步");
            return;
        }
        try {
            List<ModelEntity> allActiveModels = llmDomainService.getAllActiveModels();
            if (allActiveModels.isEmpty()) {
                logger.info("没有激活的模型需要同步");
                return;
            }
            List<ApiInstanceCreateRequest> instanceRequests = new ArrayList<>();
            for (ModelEntity model : allActiveModels) {
                instanceRequests.add(new ApiInstanceCreateRequest(model.getUserId(), model.getModelId(), "MODEL",
                        model.getId()));
            }
            gateway.batchCreateApiInstances(instanceRequests);
            logger.info("成功批量同步{}个模型到高可用网关", allActiveModels.size());
        } catch (Exception e) {
            logger.error("批量同步模型到高可用网关失败", e);
        }
    }

    public void changeModelStatusInGateway(ModelEntity model, boolean enabled, String reason) {
        if (!properties.isEnabled()) {
            logger.debug("高可用功能未启用，跳过模型状态变更: {}", model.getId());
            return;
        }
        try {
            if (enabled) {
                gateway.activateApiInstance("MODEL", model.getId());
            } else {
                gateway.deactivateApiInstance("MODEL", model.getId());
            }
            logger.info("{}高可用网关中的模型: modelId={}, reason={}", enabled ? "成功启用" : "成功禁用", model.getId(), reason);
        } catch (Exception e) {
            logger.error("变更高可用网关中的模型状态失败: modelId={}, enabled={}", model.getId(), enabled, e);
        }
    }

    public void batchRemoveModelsFromGateway(List<ModelsBatchDeletedEvent.ModelDeleteItem> deleteItems, String userId) {
        if (!properties.isEnabled()) {
            logger.debug("高可用功能未启用，跳过批量模型删除: 用户={}, 数量={}", userId, deleteItems.size());
            return;
        }
        if (deleteItems == null || deleteItems.isEmpty()) {
            logger.debug("没有要删除的模型");
            return;
        }
        try {
            List<ApiInstanceBatchDeleteRequest.ApiInstanceDeleteItem> instances = new ArrayList<>();
            for (ModelsBatchDeletedEvent.ModelDeleteItem deleteItem : deleteItems) {
                instances.add(new ApiInstanceBatchDeleteRequest.ApiInstanceDeleteItem("MODEL", deleteItem.getModelId()));
            }
            gateway.batchDeleteApiInstances(instances);
            logger.info("成功批量删除{}个模型从高可用网关，用户ID: {}", deleteItems.size(), userId);
        } catch (Exception e) {
            logger.error("批量删除模型从高可用网关失败，用户ID: {}, 数量: {}", userId, deleteItems.size(), e);
        }
    }
}
