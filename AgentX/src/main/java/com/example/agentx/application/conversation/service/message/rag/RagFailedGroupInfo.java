package com.example.agentx.application.conversation.service.message.rag;

import java.util.ArrayList;
import java.util.List;

/** RAG 分组检索失败信息 */
public class RagFailedGroupInfo {

    private List<String> datasetIds = new ArrayList<>();

    private String errorCode;

    private String errorMessage;

    public RagFailedGroupInfo() {
    }

    public RagFailedGroupInfo(List<String> datasetIds, String errorCode, String errorMessage) {
        this.datasetIds = datasetIds == null ? new ArrayList<>() : new ArrayList<>(datasetIds);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public List<String> getDatasetIds() {
        return datasetIds;
    }

    public void setDatasetIds(List<String> datasetIds) {
        this.datasetIds = datasetIds;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
