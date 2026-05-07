package com.example.agentx.interfaces.dto.user.request;

import com.example.agentx.interfaces.dto.Page;

public class QueryUserRequest extends Page {

    private String keyword;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }
}