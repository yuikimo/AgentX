package com.example.agentx.domain.token.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.agentx.infrastructure.llm.config.ProviderConfig;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.time.Duration;

/** 统一 Token 估算服务 */
@Service
public class TokenEstimatorService {

    private static final int DEFAULT_FALLBACK_TEXT_TOKENS = 1;

    private final Cache<String, TokenCountEstimator> openAiEstimatorCache = CacheBuilder.newBuilder().maximumSize(128)
            .expireAfterAccess(Duration.ofHours(2)).build();
    private final Cache<String, Integer> tokenEstimateCache = CacheBuilder.newBuilder().maximumSize(4096)
            .expireAfterAccess(Duration.ofMinutes(30)).build();

    /** 估算纯文本 Token 数 */
    public int estimateTextTokenCount(String text, ProviderConfig providerConfig) {
        String normalized = text == null ? "" : text.trim();
        if (!StringUtils.hasText(normalized)) {
            return 0;
        }

        String cacheKey = buildEstimateCacheKey(normalized, providerConfig);
        Integer cachedEstimate = tokenEstimateCache.getIfPresent(cacheKey);
        if (cachedEstimate != null) {
            return cachedEstimate;
        }

        int estimated;

        if (providerConfig != null && providerConfig.getProtocol() == ProviderProtocol.OPENAI) {
            try {
                TokenCountEstimator estimator = getOpenAiEstimator(providerConfig.getModel());
                estimated = Math.max(DEFAULT_FALLBACK_TEXT_TOKENS, estimator.estimateTokenCountInText(normalized));
                tokenEstimateCache.put(cacheKey, estimated);
                return estimated;
            } catch (Exception ignored) {
            }
        }

        estimated = estimateByHeuristic(normalized);
        tokenEstimateCache.put(cacheKey, estimated);
        return estimated;
    }

    private TokenCountEstimator getOpenAiEstimator(String modelName) {
        String cacheKey = StringUtils.hasText(modelName) ? modelName.trim() : "gpt-4o-mini";
        TokenCountEstimator cached = openAiEstimatorCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        TokenCountEstimator estimator = new OpenAiTokenCountEstimator(cacheKey);
        openAiEstimatorCache.put(cacheKey, estimator);
        return estimator;
    }

    private int estimateByHeuristic(String text) {
        return estimateByHeuristicInternal(text);
    }

    public int estimateTextTokenCountHeuristically(String text) {
        String normalized = text == null ? "" : text.trim();
        if (!StringUtils.hasText(normalized)) {
            return 0;
        }
        return estimateByHeuristicInternal(normalized);
    }

    private int estimateByHeuristicInternal(String text) {
        int cjkChars = 0;
        int latinChars = 0;
        int digits = 0;
        int others = 0;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (isCjk(current)) {
                cjkChars++;
            } else if (Character.isDigit(current)) {
                digits++;
            } else if (current < 128 && !Character.isWhitespace(current)) {
                latinChars++;
            } else if (!Character.isWhitespace(current)) {
                others++;
            }
        }

        int estimated = (int) Math.ceil(cjkChars * 1.5D) + (latinChars / 4) + (digits / 2) + others;
        return Math.max(DEFAULT_FALLBACK_TEXT_TOKENS, estimated);
    }

    private boolean isCjk(char current) {
        return (current >= '\u4E00' && current <= '\u9FFF')
                || (current >= '\u3400' && current <= '\u4DBF')
                || (current >= '\uF900' && current <= '\uFAFF')
                || (current >= '\u3000' && current <= '\u303F')
                || (current >= '\uFF00' && current <= '\uFFEF')
                || (current >= '\u3040' && current <= '\u309F')
                || (current >= '\u30A0' && current <= '\u30FF')
                || (current >= '\uAC00' && current <= '\uD7AF');
    }

    private String buildEstimateCacheKey(String normalizedText, ProviderConfig providerConfig) {
        String protocol = providerConfig != null && providerConfig.getProtocol() != null
                ? providerConfig.getProtocol().name()
                : "UNKNOWN";
        String model = providerConfig != null && StringUtils.hasText(providerConfig.getModel()) ? providerConfig.getModel()
                : "default";
        return protocol + "::" + model + "::" + normalizedText.length() + "::"
                + Integer.toUnsignedString(normalizedText.hashCode(), 36);
    }
}
