package com.example.agentx.domain.highavailability.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.highavailability.gateway.HighAvailabilityGateway;
import com.example.agentx.domain.llm.model.HighAvailabilityResult;
import com.example.agentx.domain.llm.model.ModelEntity;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.service.LLMDomainService;
import com.example.agentx.infrastructure.config.HighAvailabilityProperties;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.highavailability.constant.AffinityType;
import com.example.agentx.infrastructure.highavailability.dto.request.SelectInstanceRequest;
import com.example.agentx.infrastructure.highavailability.dto.response.ApiInstanceDTO;

@Service
public class HighAvailabilityProviderSelector {

    private static final Logger logger = LoggerFactory.getLogger(HighAvailabilityProviderSelector.class);

    private final HighAvailabilityProperties properties;
    private final HighAvailabilityGateway gateway;
    private final LLMDomainService llmDomainService;
    private final Cache<String, HighAvailabilityResult> selectionCache;
    private final AtomicInteger consecutiveSelectionFailures = new AtomicInteger();
    private volatile long circuitOpenUntilMillis;

    public HighAvailabilityProviderSelector(HighAvailabilityProperties properties, HighAvailabilityGateway gateway,
            LLMDomainService llmDomainService) {
        this.properties = properties;
        this.gateway = gateway;
        this.llmDomainService = llmDomainService;
        this.selectionCache = CacheBuilder.newBuilder().maximumSize(2048)
                .expireAfterWrite(Math.max(1, properties.getSelectionCacheTtlSeconds()), TimeUnit.SECONDS).build();
    }

    public HighAvailabilityResult selectBestProvider(ModelEntity model, String userId, String sessionId,
            List<String> fallbackChain) {
        if (!properties.isEnabled()) {
            logger.debug("高可用功能未启用，使用默认Provider选择逻辑: modelId={}", model.getId());
            return fallbackToDefaultProvider(model, sessionId);
        }

        if (isSelectionCircuitOpen()) {
            logger.warn("高可用网关短路中，直接使用默认Provider: modelId={}, sessionId={}", model.getId(), sessionId);
            return fallbackToDefaultProvider(model, sessionId);
        }

        String cacheKey = buildSelectionCacheKey(model, userId, sessionId, fallbackChain);
        HighAvailabilityResult cachedResult = selectionCache.getIfPresent(cacheKey);
        if (cachedResult != null) {
            logger.debug("命中高可用选择缓存: modelId={}, sessionId={}, instanceId={}", model.getId(), sessionId,
                    cachedResult.getInstanceId());
            return cachedResult;
        }

        try {
            SelectInstanceRequest request = new SelectInstanceRequest(userId, model.getModelId(), "MODEL");
            if (sessionId != null && !sessionId.trim().isEmpty()) {
                request.setAffinityKey(sessionId);
                request.setAffinityType(AffinityType.SESSION);
            }
            if (fallbackChain != null && !fallbackChain.isEmpty()) {
                request.setFallbackChain(fallbackChain);
            }

            ApiInstanceDTO selectedInstance = gateway.selectBestInstance(request);
            String businessId = selectedInstance.getBusinessId();
            String instanceId = selectedInstance.getId();
            ModelEntity bestModel = llmDomainService.getModelById(businessId);
            boolean switched = !model.getId().equals(bestModel.getId());
            ProviderEntity provider = llmDomainService.getProvider(bestModel.getProviderId());

            HighAvailabilityResult result = new HighAvailabilityResult(provider, bestModel, instanceId, switched);
            selectionCache.put(cacheKey, result);
            recordSelectionSuccess();
            logger.info("通过高可用网关选择Provider成功: modelId={}, bestBusinessId={}, providerId={}, sessionId={}, switched={}",
                    model.getId(), businessId, provider.getId(), sessionId, switched);
            return result;
        } catch (Exception e) {
            recordSelectionFailure();
            logger.warn("高可用网关选择Provider失败，降级到默认逻辑: modelId={}, sessionId={}", model.getId(), sessionId, e);
            return fallbackToDefaultProvider(model, sessionId);
        }
    }

    private HighAvailabilityResult fallbackToDefaultProvider(ModelEntity model, String sessionId) {
        try {
            ProviderEntity provider = llmDomainService.getProvider(model.getProviderId());
            return new HighAvailabilityResult(provider, model, null, false);
        } catch (Exception fallbackException) {
            logger.error("降级逻辑也失败了: modelId={}, sessionId={}", model.getId(), sessionId, fallbackException);
            throw new BusinessException("获取Provider失败", fallbackException);
        }
    }

    private String buildSelectionCacheKey(ModelEntity model, String userId, String sessionId, List<String> fallbackChain) {
        String fallbackPart = fallbackChain == null ? "" : fallbackChain.stream().collect(Collectors.joining(","));
        return userId + "|" + model.getId() + "|" + model.getModelId() + "|"
                + (sessionId == null ? "" : sessionId) + "|" + fallbackPart;
    }

    private boolean isSelectionCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntilMillis;
    }

    private void recordSelectionSuccess() {
        consecutiveSelectionFailures.set(0);
        circuitOpenUntilMillis = 0;
    }

    private void recordSelectionFailure() {
        int failures = consecutiveSelectionFailures.incrementAndGet();
        int threshold = Math.max(1, properties.getCircuitFailureThreshold());
        if (failures >= threshold) {
            circuitOpenUntilMillis = System.currentTimeMillis() + Math.max(1000, properties.getCircuitOpenMillis());
            consecutiveSelectionFailures.set(0);
            logger.warn("高可用网关连续失败达到阈值，进入短路: threshold={}, openMillis={}", threshold,
                    properties.getCircuitOpenMillis());
        }
    }
}
