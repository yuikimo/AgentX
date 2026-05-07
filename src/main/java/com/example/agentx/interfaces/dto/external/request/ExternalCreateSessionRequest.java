package com.example.agentx.interfaces.dto.external.request;

import jakarta.validation.constraints.Size;

/** 外部API创建会话请求DTO */
public class ExternalCreateSessionRequest {

    /** 会话标题（可选，默认"新会话"） */
    @Size(max = 20, message = "会话标题不能超过20个字")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? null : title.trim();
    }
}
