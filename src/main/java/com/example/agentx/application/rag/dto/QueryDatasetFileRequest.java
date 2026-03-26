package com.example.agentx.application.rag.dto;

import com.example.agentx.interfaces.dto.Page;

/**
 * 查询数据集文件请求
 */
public class QueryDatasetFileRequest extends Page {

    /**
     * 搜索关键词
     */
    private String keyword;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }
}