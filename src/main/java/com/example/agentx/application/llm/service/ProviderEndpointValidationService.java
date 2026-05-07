package com.example.agentx.application.llm.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.example.agentx.domain.llm.model.ProviderEntity;
import com.example.agentx.domain.llm.model.config.ProviderConfig;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.llm.protocol.enums.ProviderProtocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ProviderEndpointValidationService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);
    private static final int BODY_PREVIEW_LIMIT = 160;

    private final HttpClient httpClient;

    public ProviderEndpointValidationService() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public void validateProviderEndpoint(ProviderEntity provider) {
        if (provider == null || provider.getProtocol() != ProviderProtocol.OPENAI) {
            return;
        }
        ProviderConfig config = provider.getConfig();
        if (config == null || StringUtils.isBlank(config.getBaseUrl())) {
            return;
        }

        String baseUrl = trimTrailingSlash(config.getBaseUrl().trim());
        Set<String> endpointCandidates = buildEndpointCandidates(baseUrl);
        List<String> diagnostics = new ArrayList<>();
        for (String endpoint : endpointCandidates) {
            EndpointCheckResult checkResult = probeEndpoint(endpoint, config.getApiKey());
            if (checkResult.jsonResponse()) {
                return;
            }
            if (checkResult.htmlResponse()) {
                throw new BusinessException(String.format(
                        "服务商基础URL配置错误：请求 %s 返回了HTML页面。请填写OpenAI兼容API地址（通常以 /v1 结尾），不要填写Web控制台首页地址。",
                        endpoint));
            }
            diagnostics.add(checkResult.diagnostic());
        }

        throw new BusinessException("服务商基础URL校验失败：未获取到JSON响应，请检查基础URL、网关路由（/v1/*）或API Key。"
                + " 诊断信息: " + String.join(" ; ", diagnostics));
    }

    private EndpointCheckResult probeEndpoint(String endpoint, String apiKey) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(endpoint)).GET()
                    .timeout(READ_TIMEOUT).header("Accept", "application/json");
            if (StringUtils.isNotBlank(apiKey)) {
                requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String body = response.body() == null ? "" : response.body().trim();
            String lowerContentType = contentType.toLowerCase(Locale.ROOT);
            String lowerBody = body.toLowerCase(Locale.ROOT);

            boolean htmlResponse = lowerContentType.contains("text/html") || lowerBody.startsWith("<!doctype html")
                    || lowerBody.startsWith("<html");
            boolean jsonResponse = lowerContentType.contains("application/json") || body.startsWith("{")
                    || body.startsWith("[");
            String diagnostic = String.format("endpoint=%s,status=%d,contentType=%s,bodyPreview=%s", endpoint,
                    response.statusCode(), StringUtils.defaultIfBlank(contentType, "N/A"), buildBodyPreview(body));
            return new EndpointCheckResult(htmlResponse, jsonResponse, diagnostic);
        } catch (Exception e) {
            return new EndpointCheckResult(false, false, String.format("endpoint=%s,error=%s", endpoint,
                    StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName())));
        }
    }

    private Set<String> buildEndpointCandidates(String baseUrl) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(baseUrl + "/models");
        if (!baseUrl.endsWith("/v1")) {
            candidates.add(baseUrl + "/v1/models");
        }
        return candidates;
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String buildBodyPreview(String body) {
        if (StringUtils.isBlank(body)) {
            return "<empty>";
        }
        String sanitized = body.replaceAll("\\s+", " ");
        if (sanitized.length() <= BODY_PREVIEW_LIMIT) {
            return sanitized;
        }
        return sanitized.substring(0, BODY_PREVIEW_LIMIT) + "...";
    }

    private record EndpointCheckResult(boolean htmlResponse, boolean jsonResponse, String diagnostic) {
    }
}
