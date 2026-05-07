package com.example.agentx.interfaces.dto.conversation;

/** 会话消息分页查询参数 */
public class QueryConversationMessageRequest {

    private Integer page = 1;
    private Integer pageSize = 30;

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
