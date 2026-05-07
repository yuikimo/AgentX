package com.example.agentx.domain.rag.message;

import java.io.Serial;
import java.io.Serializable;
import com.example.agentx.domain.rag.model.ModelConfig;

/** @author shilong.zang
 * @date 09:55 <br/>
 */
public class RagDocMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 5517731583403276913L;

    /** 文件id */
    private String fileId;

    /** 文件总页数 */
    private Integer pageSize;

    /** 用户ID */
    private String userId;

    /** 会话ID（可选，用于优先解析会话/工作区模型） */
    private String sessionId;

    /** OCR模型配置 */
    private ModelConfig ocrModelConfig;

    /** 当前重试次数 */
    private Integer retryCount;

    /** 最近一次失败原因 */
    private String lastError;

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public ModelConfig getOcrModelConfig() {
        return ocrModelConfig;
    }

    public void setOcrModelConfig(ModelConfig ocrModelConfig) {
        this.ocrModelConfig = ocrModelConfig;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
