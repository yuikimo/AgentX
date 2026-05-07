package com.example.agentx.domain.highavailability.service;

import java.util.List;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.llm.event.ModelsBatchDeletedEvent;
import com.example.agentx.domain.llm.model.HighAvailabilityResult;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.service.HighAvailabilityDomainService;

/** 高可用领域服务门面，向上保持统一接口，向下按职责分发。 */
@Service
public class HighAvailabilityDomainServiceImpl implements HighAvailabilityDomainService {

    private final HighAvailabilityProviderSelector providerSelector;
    private final HighAvailabilityGatewaySyncService gatewaySyncService;
    private final HighAvailabilityCallResultReporter callResultReporter;

    public HighAvailabilityDomainServiceImpl(HighAvailabilityProviderSelector providerSelector,
            HighAvailabilityGatewaySyncService gatewaySyncService,
            HighAvailabilityCallResultReporter callResultReporter) {
        this.providerSelector = providerSelector;
        this.gatewaySyncService = gatewaySyncService;
        this.callResultReporter = callResultReporter;
    }

    @Override
    public void syncModelToGateway(ModelEntity model) {
        gatewaySyncService.syncModelToGateway(model);
    }

    @Override
    public void removeModelFromGateway(String modelId, String userId) {
        gatewaySyncService.removeModelFromGateway(modelId);
    }

    @Override
    public void updateModelInGateway(ModelEntity model) {
        gatewaySyncService.updateModelInGateway(model);
    }

    @Override
    public HighAvailabilityResult selectBestProvider(ModelEntity model, String userId) {
        return providerSelector.selectBestProvider(model, userId, null, null);
    }

    @Override
    public HighAvailabilityResult selectBestProvider(ModelEntity model, String userId, String sessionId) {
        return providerSelector.selectBestProvider(model, userId, sessionId, null);
    }

    @Override
    public HighAvailabilityResult selectBestProvider(ModelEntity model, String userId, String sessionId,
            List<String> fallbackChain) {
        return providerSelector.selectBestProvider(model, userId, sessionId, fallbackChain);
    }

    @Override
    public void reportCallResult(String instanceId, String modelId, boolean success, long latencyMs, String errorMessage) {
        callResultReporter.reportCallResult(instanceId, modelId, success, latencyMs, errorMessage);
    }

    @Override
    public void initializeProject() {
        gatewaySyncService.initializeProject();
    }

    @Override
    public void syncAllModelsToGateway() {
        gatewaySyncService.syncAllModelsToGateway();
    }

    @Override
    public void changeModelStatusInGateway(ModelEntity model, boolean enabled, String reason) {
        gatewaySyncService.changeModelStatusInGateway(model, enabled, reason);
    }

    @Override
    public void batchRemoveModelsFromGateway(List<ModelsBatchDeletedEvent.ModelDeleteItem> deleteItems, String userId) {
        gatewaySyncService.batchRemoveModelsFromGateway(deleteItems, userId);
    }
}
