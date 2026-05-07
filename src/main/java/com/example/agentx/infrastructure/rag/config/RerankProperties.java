package com.example.agentx.infrastructure.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @author shilong.zang
 * @date 13:58 <br/>
 */
@ConfigurationProperties(prefix = "rerank")
public class RerankProperties {

    /** 嵌入服务名称 */
    private String name;

    /** API密钥 */
    private String apiKey;

    /** API URL */
    private String apiUrl;

    /** 使用的模型名称 */
    private String model;

    /** 请求超时时间(毫秒) */
    private int timeout;

    /** 是否返回文档内容 */
    private boolean returnDocuments = false;

    /** 单文档最多切分块数 */
    private int maxChunksPerDoc = 10;

    /** 切分块重叠token数 */
    private int overlapTokens = 80;

    /** 单文档发送给rerank的最大字符数 */
    private int maxDocumentChars = 512;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public boolean isReturnDocuments() {
        return returnDocuments;
    }

    public void setReturnDocuments(boolean returnDocuments) {
        this.returnDocuments = returnDocuments;
    }

    public int getMaxChunksPerDoc() {
        return maxChunksPerDoc;
    }

    public void setMaxChunksPerDoc(int maxChunksPerDoc) {
        this.maxChunksPerDoc = maxChunksPerDoc;
    }

    public int getOverlapTokens() {
        return overlapTokens;
    }

    public void setOverlapTokens(int overlapTokens) {
        this.overlapTokens = overlapTokens;
    }

    public int getMaxDocumentChars() {
        return maxDocumentChars;
    }

    public void setMaxDocumentChars(int maxDocumentChars) {
        this.maxDocumentChars = maxDocumentChars;
    }
}
