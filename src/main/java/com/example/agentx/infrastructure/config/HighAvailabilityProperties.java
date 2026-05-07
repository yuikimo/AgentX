package com.example.agentx.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 高可用网关配置属性类 用于集中管理所有与高可用网关相关的配置参数
 * 
 * @author xhy
 * @since 1.0.0 */
@ConfigurationProperties(prefix = "high-availability")
public class HighAvailabilityProperties {

    /** 是否启用高可用功能 */
    private boolean enabled = false;

    /** 高可用网关基础URL */
    private String gatewayUrl;

    /** API密钥 */
    private String apiKey;

    /** 连接超时时间(毫秒)，默认5秒 */
    private int connectTimeout = 5000;

    /** 读取超时时间(毫秒)，默认10秒 */
    private int readTimeout = 10000;

    /** 选择实例连接超时时间(毫秒)，默认1秒 */
    private int selectConnectTimeout = 1000;

    /** 选择实例读取超时时间(毫秒)，默认3秒 */
    private int selectReadTimeout = 3000;

    /** 选择结果短缓存TTL(秒)，默认10秒 */
    private int selectionCacheTtlSeconds = 10;

    /** 连续失败多少次后短路高可用网关 */
    private int circuitFailureThreshold = 3;

    /** 高可用网关短路时长(毫秒)，默认30秒 */
    private long circuitOpenMillis = 30000;

    /** 调用结果缓冲刷新间隔（毫秒） */
    private long reportFlushIntervalMs = 1000;

    /** 每次刷新最多上报多少条调用结果 */
    private int reportBatchSize = 20;

    /** 调用结果上报最大重试次数 */
    private int reportMaxRetries = 2;

    /** 调用结果重试退避间隔（毫秒） */
    private long reportRetryDelayMs = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGatewayUrl() {
        return gatewayUrl + "/api";
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getSelectConnectTimeout() {
        return selectConnectTimeout;
    }

    public void setSelectConnectTimeout(int selectConnectTimeout) {
        this.selectConnectTimeout = selectConnectTimeout;
    }

    public int getSelectReadTimeout() {
        return selectReadTimeout;
    }

    public void setSelectReadTimeout(int selectReadTimeout) {
        this.selectReadTimeout = selectReadTimeout;
    }

    public int getSelectionCacheTtlSeconds() {
        return selectionCacheTtlSeconds;
    }

    public void setSelectionCacheTtlSeconds(int selectionCacheTtlSeconds) {
        this.selectionCacheTtlSeconds = selectionCacheTtlSeconds;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitOpenMillis() {
        return circuitOpenMillis;
    }

    public void setCircuitOpenMillis(long circuitOpenMillis) {
        this.circuitOpenMillis = circuitOpenMillis;
    }

    public long getReportFlushIntervalMs() {
        return reportFlushIntervalMs;
    }

    public void setReportFlushIntervalMs(long reportFlushIntervalMs) {
        this.reportFlushIntervalMs = reportFlushIntervalMs;
    }

    public int getReportBatchSize() {
        return reportBatchSize;
    }

    public void setReportBatchSize(int reportBatchSize) {
        this.reportBatchSize = reportBatchSize;
    }

    public int getReportMaxRetries() {
        return reportMaxRetries;
    }

    public void setReportMaxRetries(int reportMaxRetries) {
        this.reportMaxRetries = reportMaxRetries;
    }

    public long getReportRetryDelayMs() {
        return reportRetryDelayMs;
    }

    public void setReportRetryDelayMs(long reportRetryDelayMs) {
        this.reportRetryDelayMs = reportRetryDelayMs;
    }
}
